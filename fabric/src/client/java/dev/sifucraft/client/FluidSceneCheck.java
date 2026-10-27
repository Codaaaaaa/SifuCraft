package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.BlockSectionIndex;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Actual bucket use and scheduled fluid ticks against Native and vanilla pool lanes. */
final class FluidSceneCheck {
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final List<List<String>> trace=new ArrayList<>();
    private volatile boolean ready,finished;private int tick,stage;private ServerLevel level;private ServerPlayer player;
    private long started,retainedPendingAtGate;private int phaseTick;private int compared;
    private final boolean reconnect=Boolean.getBoolean("sifucraft.fluidReconnectSelfTest");
    private final java.nio.file.Path reconnectControl=java.nio.file.Path.of(System.getProperty("sifucraft.fluidReconnectControl"));
    private long firstNativeSession,nextNativeSession;private int disconnectedTicks;
    private static final BlockPos RECONNECT_SOURCE=new BlockPos(41,70,30),RECONNECT_FLOW=RECONNECT_SOURCE.east();
    private List<BlockState> disconnectedState;
    private List<ItemStack> reconnectInventory;private float reconnectHealth;
    private static final BlockPos GATE=new BlockPos(6,64,4);
    private static final java.nio.file.Path CONTROL=java.nio.file.Path.of(System.getProperty("sifucraft.fluidGateControl")).toAbsolutePath().normalize();
    FluidSceneCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-scene-check-"),"disposable save required");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("fluid scene: "+why);}
    void clientTick(Minecraft mc,boolean gui){if(finished){mc.stop();return;}var scene=SifuCollision.active();ready=gui&&scene!=null&&scene.placement().map().equals("FluidFixture")&&SifuCraftClient.blockCollisionCurrent()&&SceneFluidGeometry.available(mc.level);}
    private void set(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity(),"no saved BE replaced");originals.putIfAbsent(pos.immutable(),old);level.setBlockAndUpdate(pos,state);}
    private void boundaries(){
        var active=SifuCollision.active();var air=Blocks.AIR.defaultBlockState();
        for(int origin:new int[]{-4096,0,4096}){
            var scene=new SifuCollision.Active(active.grid(),new MapPlacement("Boundary",origin,64,0));
            SceneBlockSupport.withScene(scene,()->{
                for(var direction:new Direction[]{Direction.WEST,Direction.EAST}){
                    var inside=new BlockPos(origin+(direction==Direction.WEST?-2048:2047),70,30);var outside=inside.relative(direction);
                    require(SceneFluidGeometry.canOccupy(level,inside)&&!SceneFluidGeometry.canOccupy(level,outside),"half-open map extent origin="+origin+" face="+direction);
                    require(!SceneFluidGeometry.canPass(direction,level,inside,air,outside,air)&&!SceneFluidGeometry.canPass(direction.getOpposite(),level,outside,air,inside,air),"both directions reject a map boundary");
                    require(SceneFluidGeometry.canPass(direction,level,inside.relative(direction.getOpposite()),air,inside,air),"ordinary adjacent cells in the same slot remain connected");
                }
                return null;
            });
        }
        var boundary=new SifuCollision.Active(active.grid(),new MapPlacement("Boundary",-2046,64,0)); // x=2 is the next map
        var touched=new java.util.LinkedHashSet<BlockPos>();
        java.util.function.BiConsumer<BlockPos,BlockState> put=(pos,state)->{touched.add(pos.immutable());set(pos,state);};
        var source=new BlockPos(1,70,30);var foreign=source.east();
        try{
            SceneBlockSupport.withScene(boundary,()->{
                for(int x=0;x<=3;x++)for(int z=29;z<=31;z++){
                    put.accept(new BlockPos(x,69,z),Blocks.STONE.defaultBlockState());
                    put.accept(new BlockPos(x,70,z),x==0||x==3||z!=30?Blocks.STONE.defaultBlockState():air);
                }
                put.accept(source,Blocks.WATER.defaultBlockState());var state=level.getBlockState(source);state.getFluidState().tick(level,source,state);
                require(level.getFluidState(foreign).isEmpty(),"original fluid tick cannot spread into the next map slot");
                var oldFlow=Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,4);put.accept(foreign,oldFlow);
                for(int i=0;i<20;i++){var old=level.getBlockState(foreign);old.getFluidState().tick(level,foreign,old);}
                require(level.getBlockState(foreign).equals(oldFlow),"deferred foreign-map ticks preserve saved flowing liquid and level");
                return null;
            });
            SceneBlockSupport.withScene(active,()->{var state=level.getBlockState(foreign);state.getFluidState().tick(level,foreign,state);return null;});
            require(level.getBlockState(foreign).getValue(BlockStateProperties.LEVEL)==1,"same original tick resumes normally with the matching geometry");
            SceneFluidGeometry.withUnavailableGeometry(()->{
                require(!SceneFluidGeometry.canOccupy(level,source)&&!SceneFluidGeometry.canPass(Direction.EAST,level,source,air,foreign,air),"missing Native geometry is unavailable rather than empty");
                var flow=level.getBlockState(foreign);
                for(int i=0;i<20;i++)flow.getFluidState().tick(level,foreign,flow);
                require(level.getBlockState(foreign).equals(flow),"pending geometry preserves the original flowing state");
                put.accept(source,air);put.accept(foreign,Blocks.WATER.defaultBlockState());put.accept(source,Blocks.LAVA.defaultBlockState());
                require(level.getBlockState(source).is(Blocks.LAVA),"water/lava neighbor reaction waits for geometry");
                pendingBuckets(source);
                require(level.getBlockState(source).is(Blocks.LAVA)&&level.getBlockState(foreign).is(Blocks.WATER),"pending bucket queries never extract or replace sources");
                return null;
            });
            SceneBlockSupport.withScene(active,()->{put.accept(foreign,air);put.accept(foreign,Blocks.WATER.defaultBlockState());return null;});
            require(level.getBlockState(source).is(Blocks.OBSIDIAN),"pending contact resumes its original reaction after geometry returns");
            SceneBlockSupport.withScene(boundary,()->{
                put.accept(source,air);put.accept(foreign,Blocks.WATER.defaultBlockState());put.accept(source,Blocks.LAVA.defaultBlockState());
                require(level.getBlockState(source).is(Blocks.LAVA),"cross-map water cannot cool lava through direct LiquidBlock neighbor queries");return null;
            });
            SceneBlockSupport.withScene(active,()->{put.accept(foreign,air);put.accept(foreign,Blocks.WATER.defaultBlockState());return null;});
            require(level.getBlockState(source).is(Blocks.OBSIDIAN),"same-map original water/lava contact still produces obsidian");
            SceneBlockSupport.withScene(boundary,()->{
                put.accept(source,air);put.accept(foreign,Blocks.BLUE_ICE.defaultBlockState());put.accept(source.below(),Blocks.SOUL_SOIL.defaultBlockState());put.accept(source,Blocks.LAVA.defaultBlockState());
                require(level.getBlockState(source).is(Blocks.LAVA),"cross-map blue ice cannot cause basalt");return null;
            });
            SceneBlockSupport.withScene(active,()->{put.accept(foreign,air);put.accept(foreign,Blocks.BLUE_ICE.defaultBlockState());return null;});
            require(level.getBlockState(source).is(Blocks.BASALT),"same-map soul soil and blue ice retain original basalt conversion");
        }finally{for(var pos:touched){var old=originals.remove(pos);if(old!=null)level.setBlock(pos,old,2);}}
        LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] map boundaries PASS: 3 origins/6 faces, original flow rejection, foreign level preserved/resumed, water-lava and basalt negative/positive contacts");
        LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] unavailable geometry PASS: 20 original flow ticks preserve state, three actual bucket inventories/sources unchanged, liquid reaction deferred/resumed");
    }
    private void pendingBuckets(BlockPos source){
        var originalPos=player.position();float yaw=player.getYRot(),pitch=player.getXRot();var hand=player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        try{
            player.setPos(source.getX()+.5,source.getY()+1,source.getZ()+.5);player.setYRot(0);player.setXRot(90);
            for(var input:new Item[]{Items.BUCKET,Items.WATER_BUCKET,Items.LAVA_BUCKET}){
                var stack=new ItemStack(input);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
                var result=player.gameMode.useItem(player,level,stack,InteractionHand.MAIN_HAND);
                require(!result.consumesAction()&&player.getItemInHand(InteractionHand.MAIN_HAND).is(input)&&player.getItemInHand(InteractionHand.MAIN_HAND).getCount()==1,"unavailable geometry does not consume/convert bucket "+input);
            }
        }finally{player.setPos(originalPos);player.setYRot(yaw);player.setXRot(pitch);player.setItemInHand(InteractionHand.MAIN_HAND,hand);}
    }
    private long randomFireSample(SifuCollision.Active scene,BlockPos source,List<BlockPos> area,long seed,int[] fires){
        for(var pos:area)if(level.getBlockState(pos).is(Blocks.FIRE))level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);
        var random=net.minecraft.util.RandomSource.create(seed);
        SceneBlockSupport.withScene(scene,()->{level.getFluidState(source).randomTick(level,source,random);return null;});
        for(var pos:area)if(level.getBlockState(pos).is(Blocks.FIRE))fires[0]++;
        return random.nextLong();
    }
    private void randomFire(){
        var active=SifuCollision.active();var open=new SifuCollision.Active(active.grid(),active.placement());
        var touched=new java.util.LinkedHashSet<BlockPos>();var samples=new ArrayList<BlockPos>();
        java.util.function.BiConsumer<BlockPos,BlockState> put=(pos,state)->{touched.add(pos.immutable());set(pos,state);};
        var air=Blocks.AIR.defaultBlockState();var lava=Blocks.LAVA.defaultBlockState();
        int[] closedFires={0},openFires={0},boundaryFires={0},ordinaryFires={0},unavailableFires={0};
        try{
            for(int x=3;x<=8;x++)for(int y=64;y<=67;y++)for(int z=2;z<=6;z++){
                var pos=new BlockPos(x,y,z);put.accept(pos,air);samples.add(pos);
            }
            for(int y=64;y<=66;y++)for(int z=3;z<=5;z++)put.accept(new BlockPos(7,y,z),Blocks.OAK_PLANKS.defaultBlockState());
            var source=new BlockPos(5,64,4);put.accept(source,lava);
            require(level.canSpreadFireAround(source),"original lava random-fire gamerule enabled");
            for(int seed=0;seed<128;seed++){
                long closed=randomFireSample(active,source,samples,seed,closedFires),opened=randomFireSample(open,source,samples,seed,openFires);
                require(closed==opened,"Native fire output guard preserves original random sampling seed="+seed);
            }
            require(closedFires[0]==0&&openFires[0]>0,"Native closed gate blocks random lava ignition, open gate permits it: closed="+closedFires[0]+" open="+openFires[0]);
            for(var pos:samples)if(level.getBlockState(pos).is(Blocks.FIRE))level.setBlock(pos,air,2);
            samples.clear();
            var boundary=new SifuCollision.Active(active.grid(),new MapPlacement("Boundary",-2046,64,0));
            for(int x=-1;x<=4;x++)for(int y=70;y<=73;y++)for(int z=38;z<=42;z++){
                var pos=new BlockPos(x,y,z);put.accept(pos,air);samples.add(pos);
            }
            var local=new BlockPos(1,70,40);var foreign=local.east();
            for(int y=70;y<=72;y++)for(int z=39;z<=41;z++)put.accept(new BlockPos(2,y,z),Blocks.OAK_PLANKS.defaultBlockState());
            put.accept(local,lava);
            for(int seed=0;seed<128;seed++){
                randomFireSample(boundary,local,samples,seed,boundaryFires);
                randomFireSample(active,local,samples,seed,ordinaryFires);
                final int selected=seed;SceneFluidGeometry.withUnavailableGeometry(()->randomFireSample(active,local,samples,selected,unavailableFires));
            }
            require(boundaryFires[0]==0&&ordinaryFires[0]>0&&unavailableFires[0]==0,"foreign fuel and pending geometry cannot cause lava fire; same-map positive control ignites");
            for(var pos:samples)put.accept(pos,air);
            for(int y=70;y<=72;y++)for(int z=39;z<=41;z++)put.accept(new BlockPos(1,y,z),Blocks.OAK_PLANKS.defaultBlockState());
            put.accept(foreign,lava);
            for(int seed=0;seed<128;seed++)randomFireSample(boundary,foreign,samples,seed,boundaryFires);
            require(boundaryFires[0]==0,"foreign lava cannot ignite the active map");
        }finally{for(var pos:touched){var old=originals.remove(pos);if(old!=null)level.setBlock(pos,old,2);}}
        LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] lava random fire PASS: closed={} open={} boundary={} same_map={} unavailable={} RNG=128 equal seed pairs",closedFires[0],openFires[0],boundaryFires[0],ordinaryFires[0],unavailableFires[0]);
    }
    private void bucket(int offset,Item input,Item output){
        var originalPos=player.position();float yaw=player.getYRot(),pitch=player.getXRot();var hand=player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        try{
            player.setPos(4.5+offset,65,4.5);player.setYRot(0);player.setXRot(90);var stack=new ItemStack(input);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            var result=player.gameMode.useItem(player,level,stack,InteractionHand.MAIN_HAND);
            require(result.consumesAction()&&player.getItemInHand(InteractionHand.MAIN_HAND).is(output)&&player.getItemInHand(InteractionHand.MAIN_HAND).getCount()==1,"actual bucket inventory conversion offset="+offset+" result="+result+" hand="+player.getItemInHand(InteractionHand.MAIN_HAND));
        }finally{player.setPos(originalPos);player.setYRot(yaw);player.setXRot(pitch);player.setItemInHand(InteractionHand.MAIN_HAND,hand);}
    }
    private List<String> fluid(int offset){var out=new ArrayList<String>();for(int y=62;y<=65;y++)for(int z=1;z<=7;z++)for(int x=1;x<=15;x++){
        var pos=new BlockPos(x+offset,y,z);var state=level.getFluidState(pos);if(!state.isEmpty())out.add((x)+","+y+","+z+":"+state.toString());
    }return out;}
    private void revalidation(){
        var index=new BlockSectionIndex();
        // These are only work-list entries in a private index. They do not
        // install 1024 fake stones in the world or the Native collision channel.
        for(int i=0;i<1024;i++)index.changed(new BlockPos(-1-(i&15),64+((i>>4)&15),(i>>8)&3),Blocks.STONE.defaultBlockState());
        set(GATE,Blocks.WATER.defaultBlockState());index.changed(GATE,Blocks.WATER.defaultBlockState());
        var scene=SifuCollision.active();var scope=WorldMotionWorld.current().scope();var updates=new SceneBlockSupport.Updates();
        for(int revision=0;revision<10;revision++)updates.update(level,index,new SifuCollision.Active(scene.grid(),scene.placement(),scene.doors(),scope.nativeSession(),scope.world()));
        require(level.getFluidState(GATE).isEmpty(),"first-grid buried source is removed despite 1024 earlier positions and a fresh geometry revision every work batch");
        var lateIndex=new BlockSectionIndex();var late=new SceneBlockSupport.Updates();late.update(level,lateIndex,scene);
        set(GATE,Blocks.WATER.defaultBlockState());lateIndex.replace(net.minecraft.core.SectionPos.of(GATE).asLong(),Map.of(net.minecraft.core.SectionPos.sectionRelativePos(GATE),Blocks.WATER.defaultBlockState()));
        late.update(level,lateIndex,scene);
        require(level.getFluidState(GATE).isEmpty(),"saved fluid loaded after the initial geometry is revalidated without waiting for a door change");
        LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] initial-load/rapid-revision 1025-position bounded revalidation PASS");
    }
    void tick(MinecraftServer server){
        if(finished||stage==0&&!ready||stage==8&&!ready)return;
        try{
            if(stage==0){
                level=server.overworld();if(server.getPlayerList().getPlayers().isEmpty()||SceneFluidGeometry.canOccupy(level,GATE))return;player=server.getPlayerList().getPlayers().getFirst();started=System.currentTimeMillis();boundaries();randomFire();revalidation();
                for(int offset:new int[]{0,20})for(int y=62;y<=66;y++)for(int z=1;z<=7;z++)for(int x=1;x<=15;x++)set(new BlockPos(x+offset,y,z),Blocks.AIR.defaultBlockState());
                for(int z=2;z<=6;z++)for(int x=2;x<=6;x++){
                    set(new BlockPos(x+20,63,z),Blocks.STONE.defaultBlockState());
                    if(x==2||x==6||z==2||z==6)for(int y=64;y<=65;y++)set(new BlockPos(x+20,y,z),Blocks.STONE.defaultBlockState());
                }
                for(int x=7;x<=14;x++)for(int z=3;z<=5;z++){
                    set(new BlockPos(x+20,63,z),Blocks.STONE.defaultBlockState());
                    if(z!=4||x==14)for(int y=64;y<=65;y++)set(new BlockPos(x+20,y,z),Blocks.STONE.defaultBlockState());
                }
                require(!SceneFluidGeometry.canOccupy(level,new BlockPos(2,64,4))&&SceneFluidGeometry.canOccupy(level,new BlockPos(4,64,4)),"Native pool geometry active");
                for(int offset:new int[]{0,20}){bucket(offset,Items.WATER_BUCKET,Items.BUCKET);require(level.getFluidState(new BlockPos(4+offset,64,4)).isSource(),"bucket places on actual floor without an extra block height");bucket(offset,Items.BUCKET,Items.WATER_BUCKET);require(level.getFluidState(new BlockPos(4+offset,64,4)).isEmpty(),"actual bucket collects the source");bucket(offset,Items.WATER_BUCKET,Items.BUCKET);}
                require(!((BucketItem)Items.WATER_BUCKET).emptyContents(player,level,new BlockPos(2,64,4),null),"dispenser-style bucket cannot fill Native wall");
                stage=1;LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] actual bucket source/pickup/inventory PASS; scheduled Native/reference pool traces started");return;
            }
            require(System.currentTimeMillis()-started<(reconnect?60000:30000),"bounded fixture deadline stage="+stage+" compared="+compared+" control="+CONTROL);
            if(stage>=9){reconnectTick(server);return;}
            if(stage==1){
                var actual=compare();tick++;
                if(tick<160)return;
                require(actual.size()==9,"3x3 source/spread stays above Native floor and inside Native walls: "+actual);
                retainedPendingAtGate=SceneFluidGeometry.retainedPendingPublications();gate(false);stage=2;return;
            }
            if(stage==2||stage==4||stage==6){
                if(!SceneFluidGeometry.available(level))return;
                boolean closing=stage==4;if(SceneFluidGeometry.canOccupy(level,GATE)==closing)return;
                for(int y=64;y<=65;y++)set(new BlockPos(26,y,4),(closing?Blocks.STONE:Blocks.AIR).defaultBlockState());
                phaseTick=0;stage++;return;
            }
            if(stage==3||stage==5||stage==7){
                compare();if(++phaseTick<40)return;
                if(stage==3||stage==7)require(!level.getFluidState(GATE.east()).isEmpty(),"opening/reopening the Native gate restarts actual scheduled flow");
                else require(level.getFluidState(GATE).isEmpty(),"closing Native geometry removes buried liquid rather than retaining an old source");
                if(stage==3){gate(true);stage=4;return;}
                if(stage==5){gate(false);stage=6;return;}
                if(Long.getLong("sifucraft.fluidGeometryLoadDelayMs",0)>0)require(SceneFluidGeometry.retainedPendingPublications()>retainedPendingAtGate,"real asynchronous door loading retained the old grid while fluid geometry was unavailable");
                LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] retained old-grid pending publications={}",SceneFluidGeometry.retainedPendingPublications()-retainedPendingAtGate);
                if(reconnect){beginReconnect();return;}
                complete(server);
            }else if(stage==8)finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[fluid-scene-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    private void reconnectControl(String value)throws java.io.IOException{java.nio.file.Files.writeString(reconnectControl,value);}
    private List<BlockState> reconnectState(){return List.of(level.getBlockState(RECONNECT_SOURCE),level.getBlockState(RECONNECT_FLOW));}
    private void beginReconnect()throws java.io.IOException{
        var view=WorldMotionWorld.current();require(view!=null,"fresh initial Native scope");firstNativeSession=view.scope().nativeSession();
        reconnectHealth=player.getHealth();reconnectInventory=java.util.stream.IntStream.range(0,player.getInventory().getContainerSize()).mapToObj(i->player.getInventory().getItem(i).copy()).toList();
        for(int x=40;x<=43;x++)for(int z=29;z<=31;z++){
            set(new BlockPos(x,69,z),Blocks.STONE.defaultBlockState());
            set(new BlockPos(x,70,z),x==40||x==43||z!=30?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        }
        set(RECONNECT_SOURCE,Blocks.WATER.defaultBlockState());set(RECONNECT_FLOW,Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,4));
        stage=9;reconnectControl("1:disconnect");LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] disconnect requested; same MC server remains running");
    }
    private void reconnectTick(MinecraftServer server)throws java.io.IOException{
        require(server.getPlayerList().getPlayer(player.getUUID())==player&&player.getHealth()==reconnectHealth,"same MC player and health retained across the Native endpoint lifecycle");
        for(int i=0;i<reconnectInventory.size();i++){
            var old=reconnectInventory.get(i);var current=player.getInventory().getItem(i);
            require(old.getCount()==current.getCount()&&ItemStack.isSameItemSameComponents(old,current),"no inventory loss or starter duplication across Native reconnect slot="+i);
        }
        if(stage==9){
            if(SifuCraftClient.attachedToSifu()||SceneFluidGeometry.available(level))return;
            set(RECONNECT_FLOW,Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,4));disconnectedState=reconnectState();stage=10;return;
        }
        if(stage==10){
            require(!SifuCraftClient.attachedToSifu()&&!SceneFluidGeometry.available(level),"Native is absent and geometry stays unavailable in the same MC world");
            require(disconnectedState.equals(reconnectState()),"actual scheduled fluid ticks preserve state through Native disconnection");
            if(++disconnectedTicks<60)return;stage=11;reconnectControl("3:reconnect");
            LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] 60 disconnected world ticks preserved; new Native session requested");return;
        }
        if(!SceneFluidGeometry.available(level)){require(disconnectedState.equals(reconnectState()),"fluid remains unchanged until replacement geometry is loaded");return;}
        if(!ready)return;var view=WorldMotionWorld.current();if(view==null)return;
        nextNativeSession=view.scope().nativeSession();require(nextNativeSession!=0&&nextNativeSession!=firstNativeSession,"a distinct actual Native process/session reconnected");
        var flow=level.getBlockState(RECONNECT_FLOW);if(!flow.is(Blocks.WATER)||flow.getValue(BlockStateProperties.LEVEL)==4)return;
        LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] actual Native reconnect PASS same server, 60 paused ticks, source retained, flow level=4->{} sessions={}->{}",flow.getValue(BlockStateProperties.LEVEL),Long.toUnsignedString(firstNativeSession),Long.toUnsignedString(nextNativeSession));
        reconnectControl("0:done");complete(server);
    }
    private void complete(MinecraftServer server)throws java.io.IOException{
        restore(server);stage=8;
        var report=new LinkedHashMap<String,Object>(Map.of("result","PASS","scheduled_ticks",compared,"bucket_conversions",6,"native_wall_bucket_rejected",true,"native_gate_open_close_reopen",true,"map_boundaries",true,"foreign_tick_preserved_and_resumed",true,"boundary_mix_contacts",true,"pending_geometry_preserves_fluid_and_buckets",true,"trace",trace));
        report.put("lava_random_fire_seed_pairs",128);
        if(reconnect)report.put("native_reconnect",Map.of("disconnected_ticks",disconnectedTicks,"first_session",Long.toUnsignedString(firstNativeSession),"next_session",Long.toUnsignedString(nextNativeSession)));
        AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-fluid-scene.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
        LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] PASS {} scheduled ticks equal ordinary vanilla pool; bucket source/pickup/inventory, no floor/wall leak, Native gate open/close/reopen; reconnect={}",compared,reconnect);
    }
    private List<String> compare(){var actual=fluid(0);var reference=fluid(20);require(actual.equals(reference),"scheduled phase="+stage+" tick="+phaseTick+" Native/reference mismatch actual="+actual+" reference="+reference);trace.add(actual);compared++;return actual;}
    private static void gate(boolean closed)throws java.io.IOException{java.nio.file.Files.writeString(CONTROL,closed?"1":"0");}
    void restore(MinecraftServer ignored){if(level!=null)for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}
}
