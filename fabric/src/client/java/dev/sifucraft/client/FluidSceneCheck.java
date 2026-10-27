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
                level=server.overworld();if(server.getPlayerList().getPlayers().isEmpty()||SceneFluidGeometry.canOccupy(level,GATE))return;player=server.getPlayerList().getPlayers().getFirst();started=System.currentTimeMillis();boundaries();revalidation();
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
            require(System.currentTimeMillis()-started<30000,"bounded fixture deadline stage="+stage+" compared="+compared+" control="+CONTROL);
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
                restore(server);stage=8;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-fluid-scene.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","scheduled_ticks",compared,"bucket_conversions",6,"native_wall_bucket_rejected",true,"native_gate_open_close_reopen",true,"map_boundaries",true,"foreign_tick_preserved_and_resumed",true,"boundary_mix_contacts",true,"pending_geometry_preserves_fluid_and_buckets",true,"trace",trace),out));
                LoggerFactory.getLogger("sifucraft").info("[fluid-scene-test] PASS {} scheduled ticks equal ordinary vanilla pool; bucket source/pickup/inventory, no floor/wall leak, Native gate open/close/reopen",compared);
            }else if(stage==8)finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[fluid-scene-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    private List<String> compare(){var actual=fluid(0);var reference=fluid(20);require(actual.equals(reference),"scheduled phase="+stage+" tick="+phaseTick+" Native/reference mismatch actual="+actual+" reference="+reference);trace.add(actual);compared++;return actual;}
    private static void gate(boolean closed)throws java.io.IOException{java.nio.file.Files.writeString(CONTROL,closed?"1":"0");}
    void restore(MinecraftServer ignored){if(level!=null)for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}
}
