package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PlayerSignalBody;
import dev.sifucraft.blocks.SignalEntities;
import dev.sifucraft.blocks.SignalSteps;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Installed vanilla sensors, projectile hits, rail inputs and menu transactions, in a disposable save. */
final class SensorCheck {
    private static final List<String> CASES=List.of("reference","npc","native_player_body","wool_floor","wool_wall","idle","air","teleport","calibrated_reject_step","calibrated_accept_explode");
    private final BlockPos base=new BlockPos(16,201,16),sensor=base.offset(4,0,5),calibrated=base.offset(9,0,5);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Set<ChunkPos> chunks=new HashSet<>(),forced=new HashSet<>();
    private final Map<String,List<Map<String,Object>>> traces=new LinkedHashMap<>();
    private final Map<String,Object> extras=new LinkedHashMap<>();
    private final List<Entity> entities=new ArrayList<>();
    private DynamicGameEventListener<GameEventListener> listener;
    private SignalSteps playerSteps;
    private Entity actor;
    private ServerLevel level;
    private ServerPlayer player;
    private int phase=-1,tick,warmup,extraTick,rawSteps;
    private boolean done;
    private long started;
    private final boolean needNative=Boolean.getBoolean("sifucraft.signalNativeTest");
    private volatile boolean ready,finished;
    private volatile long restoredAt;
    private boolean clientDone;
    private int firstHit=-1,lastHit=-1;
    private final Block wool=BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:white_wool"));
    private Holder<net.minecraft.world.clock.WorldClock> clock;
    private long originalTime;
    SensorCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-sensor-check-"),"disposable save required");}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError("sensors: "+message);}
    void clientTick(Minecraft mc,boolean guiReady){
        if(clientDone)return;ready=guiReady&&SifuCraftClient.blockCollisionCurrent();
        if(!finished)return;
        require(System.currentTimeMillis()-restoredAt<30000,"cleanup Native ACK deadline");
        if(System.currentTimeMillis()-restoredAt<1000||!SifuCraftClient.blockCollisionCurrent())return;
        write(mc.getSingleplayerServer(),"p8-sensors-native-ack.json",Map.of("result","PASS","fixture_restored",true,"native_revision_applied",true));
        clientDone=true;mc.stop();
    }
    private static void write(MinecraftServer server,String name,Object report){
        try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve(name),out->new GsonBuilder().setPrettyPrinting().create().toJson(report,out));}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private void set(BlockPos pos,BlockState state){
        var chunk=new ChunkPos(pos.getX()>>4,pos.getZ()>>4);
        if(chunks.add(chunk)&&level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);
        originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));require(!originals.get(pos).hasBlockEntity(),"no original fixture BE");
        level.setBlockAndUpdate(pos,state);
    }
    private void block(BlockPos pos,BlockState state){set(pos.below(),Blocks.STONE.defaultBlockState());set(pos,state);}
    private Entity create(String type){
        var entity=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:"+type)).create(level,EntitySpawnReason.COMMAND);
        require(entity!=null,"entity created: "+type);return entity;
    }
    private void cleanupActor(){
        if(listener!=null){listener.remove(level);listener=null;}
        for(var entity:entities)entity.discard();entities.clear();actor=null;
    }
    private void prepareCase(int next){
        cleanupActor();phase=next;tick=0;warmup=0;rawSteps=0;
        for(int x=-2;x<=17;x++)for(int z=-1;z<=6;z++)set(base.offset(x,-1,z),(phase==3?wool:Blocks.STONE).defaultBlockState());
        for(int x=-2;x<=17;x++)for(int y=0;y<3;y++)set(base.offset(x,y,2),(phase==4?wool:Blocks.AIR).defaultBlockState());
        set(sensor,Blocks.AIR.defaultBlockState());set(calibrated,Blocks.AIR.defaultBlockState());
        set(sensor,Blocks.SCULK_SENSOR.defaultBlockState());
        set(calibrated,Blocks.CALIBRATED_SCULK_SENSOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
        set(calibrated.west(),(phase>=8?Blocks.REDSTONE_BLOCK:Blocks.AIR).defaultBlockState());
        if(phase==0||phase>=8){var cow=(Mob)create("cow");cow.setNoAi(true);actor=cow;}
        else if(phase==2)actor=new PlayerSignalBody(SignalEntities.PLAYER_BODY,level);
        else {
            var proxy=new EnemyProxy(EnemyEntities.ENEMY_PROXY,level);
            proxy.mirror(new EnemyFrame.Entry(9001,1,SifuFrame.Vec3.ZERO,0,35,92,60,60,Protocol.ENEMY_ALIVE,1,SifuFrame.Vec3.ZERO),new MapPlacement("sensors",0,0,0));actor=proxy;
        }
        actor.setPos(base.getX()+.5,base.getY()+(phase==6?1:0),base.getZ()+.5);
        actor.setNoGravity(true);actor.setSilent(true);actor.setPermanentlyInvulnerable(true);actor.setDeltaMovement(Vec3.ZERO);
        require(level.addFreshEntity(actor),"actor added");entities.add(actor);playerSteps=new SignalSteps();
        if(actor instanceof EnemyProxy p)p.updateSignals();else if(actor instanceof PlayerSignalBody)playerSteps.update(actor);
        listener=new DynamicGameEventListener<>(new GameEventListener(){
            @Override public PositionSource getListenerSource(){return new BlockPositionSource(base.offset(5,0,0));}
            @Override public int getListenerRadius(){return 16;}
            @Override public boolean handleGameEvent(ServerLevel source,Holder<GameEvent> event,GameEvent.Context context,Vec3 pos){
                if(event==GameEvent.STEP&&context.sourceEntity()==actor)rawSteps++;return false;
            }
        });listener.add(level);
        traces.put(CASES.get(phase),new ArrayList<>());
    }
    private int power(BlockPos pos){return level.getBlockState(pos).getValue(BlockStateProperties.POWER);}
    private void sampleWalk(){
        require(actor.isAlive()&&!actor.isRemoved(),"walking actor remains alive");
        if(tick>=2&&tick<42&&phase<8&&phase!=5){
            double dx=phase==7?6:.2;
            if(actor instanceof Mob&&!(actor instanceof EnemyProxy)&&!(actor instanceof PlayerSignalBody))actor.move(MoverType.SELF,new Vec3(dx,-.01,0));
            else actor.setPos(actor.position().add(dx,0,0));
        }
        if(phase==8&&tick==4)level.gameEvent(GameEvent.STEP,actor.position(),GameEvent.Context.of(actor,Blocks.STONE.defaultBlockState()));
        if(phase==9&&tick==4)level.gameEvent(GameEvent.EXPLODE,actor.position(),GameEvent.Context.of(actor,Blocks.STONE.defaultBlockState()));
        var before=actor.position();var velocity=actor.getDeltaMovement();float hp=actor instanceof Mob m?m.getHealth():0;
        if(actor instanceof EnemyProxy p)p.updateSignals();else if(actor instanceof PlayerSignalBody)playerSteps.update(actor);
        require(before.equals(actor.position())&&velocity.equals(actor.getDeltaMovement())&&(!(actor instanceof Mob m)||hp==m.getHealth()),"signal emissions preserve pose/velocity/HP");
        actor.setDeltaMovement(Vec3.ZERO);
        var s=level.getBlockState(sensor);var c=level.getBlockState(calibrated);
        for(var pos:List.of(sensor,calibrated))if(WorldBlocks.ENABLED){var snapshot=WorldBlocks.index().snapshot(SectionPos.of(pos).asLong());
            require(snapshot!=null&&snapshot.states().get(SectionPos.sectionRelativePos(pos))==level.getBlockState(pos),"sensor state index matches vanilla");}
        traces.get(CASES.get(phase)).add(Map.of("tick",tick,"steps",rawSteps,"sensor",BlockStateKey.encode(s),"calibrated",BlockStateKey.encode(c),
            "frequency",((SculkSensorBlockEntity)level.getBlockEntity(sensor)).getLastVibrationFrequency(),"calibrated_frequency",((SculkSensorBlockEntity)level.getBlockEntity(calibrated)).getLastVibrationFrequency()));
        if(++tick<96)return;
        var rows=traces.get(CASES.get(phase));boolean lit=rows.stream().anyMatch(row->row.get("sensor").toString().contains("power=")&&!row.get("sensor").toString().contains("power=0,"));
        if(phase==0)require(rawSteps>0&&lit,"vanilla reference walking emits steps and activates sensor");
        if(phase==1||phase==2){require((rawSteps>0)==WorldBlocks.ENABLED,"snapshot walking enabled gate");
            if(WorldBlocks.ENABLED)require(rows.equals(traces.get("reference")),"snapshot step cadence and both sensor traces equal vanilla reference: "+CASES.get(phase));}
        if(phase>=3&&phase<=7)require(!lit,"wool/occlusion/idle/air/teleport rejects sensor: "+CASES.get(phase));
        if((phase==3||phase==4)&&WorldBlocks.ENABLED)require(rawSteps>0,"wool negative includes actual emitted walking vibrations");
        if(phase==8)require(rows.stream().noneMatch(row->row.get("calibrated").toString().matches(".*power=([1-9]|1[0-5]),.*")),"rear 15 rejects STEP frequency 1");
        if(phase==9)require(rows.stream().anyMatch(row->((Number)row.get("calibrated_frequency")).intValue()==15),"rear 15 accepts actual EXPLODE frequency");
        if(phase+1<CASES.size())prepareCase(phase+1);else{cleanupActor();phase++;prepareExtras();}
    }
    private void use(BlockPos pos){level.getBlockState(pos).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
    private BlockPos at(int x,int z){return base.offset(x,0,z);}
    private void prepareExtras(){
        set(sensor,Blocks.AIR.defaultBlockState());set(calibrated,Blocks.AIR.defaultBlockState());
        for(var b:List.of(Blocks.POWERED_RAIL,Blocks.ACTIVATOR_RAIL)){
            int z=b==Blocks.POWERED_RAIL?12:16;
            for(int x=0;x<16;x++)block(at(x,z),b.defaultBlockState().setValue(((BaseRailBlock)b).getShapeProperty(),RailShape.EAST_WEST));
            set(at(-1,z),Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        block(at(0,20),Blocks.DETECTOR_RAIL.defaultBlockState());
        block(at(0,24),Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        clock=level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        originalTime=level.getOverworldClockTime();level.getServer().clockManager().setTotalTicks(clock,6000);
        extras.put("menu_transactions",checkMenus());
    }
    private List<Map<String,Object>> checkMenus(){
        var results=new ArrayList<Map<String,Object>>();int x=0;
        for(var b:List.of(Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.BARREL,Blocks.HOPPER,Blocks.DISPENSER,Blocks.DROPPER)){
            var pos=at(x,30);x+=3;block(pos,b.defaultBlockState());use(pos);
            var menu=player.containerMenu;require(menu!=player.inventoryMenu,"original menu opens "+b);
            var container=(net.minecraft.world.Container)level.getBlockEntity(pos);container.setItem(0,new ItemStack(Items.DIAMOND,3));
            menu.clicked(0,0,ContainerInput.PICKUP,player);require(menu.getCarried().getCount()==3&&container.getItem(0).isEmpty(),"menu pickup conserved");
            menu.clicked(1,0,ContainerInput.PICKUP,player);require(menu.getCarried().isEmpty()&&container.getItem(1).getCount()==3,"menu placement conserved");
            if(b==Blocks.TRAPPED_CHEST)require(ChestBlockEntity.getOpenCount(level,pos)==1,"trapped chest opens once");
            results.add(Map.of("block",BuiltInRegistries.BLOCK.getKey(b).toString(),"menu",BuiltInRegistries.MENU.getKey(menu.getType()).toString(),"items",3));
            player.closeContainer();if(b==Blocks.TRAPPED_CHEST)require(ChestBlockEntity.getOpenCount(level,pos)==0,"trapped chest closes");
        }
        var left=at(0,34);var right=left.east();
        block(left,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,ChestType.LEFT));
        block(right,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,ChestType.RIGHT));use(left);
        require(player.containerMenu!=player.inventoryMenu&&player.containerMenu.getType()==MenuType.GENERIC_9x6,"double chest menu has 54 container slots");player.closeContainer();
        results.add(Map.of("block","double_chest","menu","minecraft:generic_9x6","items",0));
        var lectern=at(8,34);block(lectern,Blocks.LECTERN.defaultBlockState());
        var book=new ItemStack(Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,new net.minecraft.world.item.component.WritableBookContent(List.of(
            net.minecraft.server.network.Filterable.passThrough("sensor fixture page one"),net.minecraft.server.network.Filterable.passThrough("sensor fixture page two"),net.minecraft.server.network.Filterable.passThrough("sensor fixture page three"))));
        var ownedBook=book.copy();
        require(LecternBlock.tryPlaceBook(player,level,lectern,level.getBlockState(lectern),book)&&book.isEmpty(),"lectern consumes one book");use(lectern);
        require(player.containerMenu instanceof LecternMenu,"lectern opens original page menu");
        var menu=(LecternMenu)player.containerMenu;var entity=(LecternBlockEntity)level.getBlockEntity(lectern);
        require(entity.getRedstoneSignal()==1,"lectern first page strength");
        require(menu.clickMenuButton(player,LecternMenu.BUTTON_NEXT_PAGE)&&entity.getRedstoneSignal()==8,"lectern middle page strength");
        require(menu.clickMenuButton(player,LecternMenu.BUTTON_NEXT_PAGE)&&entity.getRedstoneSignal()==15,"lectern last page strength");
        require(level.getBlockState(lectern).getValue(BlockStateProperties.POWERED),"page change emits original pulse");
        require(menu.clickMenuButton(player,LecternMenu.BUTTON_TAKE_BOOK)&&!entity.hasBook(),"lectern take book");
        boolean removed=false;var inventory=player.getInventory();
        for(int i=0;i<inventory.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(inventory.getItem(i),ownedBook)){inventory.removeItem(i,1);removed=true;break;}
        require(removed,"taken book conserved and removed as owned fixture");player.closeContainer();
        results.add(Map.of("block","minecraft:lectern","menu","minecraft:lectern","page_strengths",List.of(1,8,15),"items",1));
        return results;
    }
    private void sampleExtras(){
        if(extraTick==2){
            var cart=(MinecartChest)create("chest_minecart");cart.setPos(Vec3.atBottomCenterOf(at(0,20)).add(0,.1,0));cart.setNoGravity(true);cart.noPhysics=true;
            cart.setItem(0,new ItemStack(Items.STONE,64));require(level.addFreshEntity(cart),"container minecart added");entities.add(cart);
        }
        if(extraTick>=2&&extraTick<18&&!entities.isEmpty()){
            var cart=entities.getFirst();cart.setPos(Vec3.atBottomCenterOf(at(0,20)).add(0,.1,0));cart.setDeltaMovement(Vec3.ZERO);
            var state=level.getBlockState(at(0,20));state.entityInside(level,at(0,20),cart,InsideBlockEffectApplier.NOOP,false);
        }
        if(extraTick==12){
            require(level.getBlockState(at(0,20)).getValue(BlockStateProperties.POWERED),"detector rail sees minecart");
            require(level.getBlockState(at(0,20)).getAnalogOutputSignal(level,at(0,20),Direction.UP)==1,"detector rail reads cargo fullness");
            for(int z:new int[]{12,16}){var values=new ArrayList<Boolean>();for(int x=0;x<16;x++)values.add(level.getBlockState(at(x,z)).getValue(BlockStateProperties.POWERED));
                require(values.getFirst()&&!values.getLast(),"powered rail propagation has vanilla finite range");extras.put("rail_"+z,values);}
        }
        if(extraTick==18){for(var entity:entities)entity.discard();entities.clear();}
        if(extraTick==32){int day=power(at(0,24));require(day>0,"uncovered daylight detector at noon");extras.put("daylight_noon",day);
            level.getServer().clockManager().setTotalTicks(clock,18000);}
        if(extraTick==48)require(!level.getBlockState(at(0,20)).getValue(BlockStateProperties.POWERED),"detector releases after cart removed");
        if(extraTick==64){int night=power(at(0,24));require(night==0,"normal daylight detector at midnight");extras.put("daylight_midnight",night);}
        int targetPhase=(extraTick-56)/40;
        if(extraTick>=56&&targetPhase<4){int local=(extraTick-56)%40;var pos=at(0,40);
            if(local==0){set(pos,Blocks.TARGET.defaultBlockState());firstHit=-1;lastHit=-1;
                var projectile=(Projectile)create(targetPhase<2?"arrow":"snowball");double offset=targetPhase%2==0?.5:.99;
                projectile.setPos(pos.getX()+offset,pos.getY()+offset,pos.getZ()-2);projectile.setNoGravity(true);projectile.setDeltaMovement(0,0,.5);
                require(level.addFreshEntity(projectile),"actual projectile spawned");entities.add(projectile);}
            int p=level.getBlockState(pos).getValue(BlockStateProperties.POWER);
            if(p>0){if(firstHit<0)firstHit=local;lastHit=local;require(p==(targetPhase%2==0?15:1),"target center/edge strength");}
            if(local==39){require(firstHit>=0&&lastHit-firstHit+1==(targetPhase<2?20:8),"actual target arrow/other pulse duration");extras.put("target_"+targetPhase,Map.of("ticks",lastHit-firstHit+1,"strength",targetPhase%2==0?15:1));cleanupActor();}
        }
        if(extraTick==220){use(at(0,24));require(level.getBlockState(at(0,24)).getValue(BlockStateProperties.INVERTED),"daylight detector inversion via original use");
            extras.put("daylight_inverted",level.getBlockState(at(0,24)).getValue(BlockStateProperties.POWER));}
        extraTick++;
    }
    void tick(MinecraftServer server){
        if(done||server.getPlayerList().getPlayers().isEmpty())return;
        if(needNative&&level==null&&!ready)return;
        try{
            if(level==null){level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();started=System.currentTimeMillis();prepareCase(0);return;}
            require(System.currentTimeMillis()-started<180000,"fixture deadline");
            if(chunks.stream().anyMatch(c->!level.isPositionEntityTicking(new BlockPos(c.x()*16+8,base.getY(),c.z()*16+8))))return;
            if(warmup++<40)return;
            if(phase<CASES.size()){sampleWalk();return;}
            sampleExtras();if(extraTick<240)return;
            write(server,"p8-sensors.json",Map.of("result","PASS","derived_index",WorldBlocks.ENABLED,"walking",traces,"extras",extras,"actual_native_motion",false));
            done=true;restore(server);LoggerFactory.getLogger("sifucraft").info("[sensor-test] PASS walking/sensors/calibration/negative contacts, projectile targets, rail inputs and menus; index={}",WorldBlocks.ENABLED);
            if(needNative){restoredAt=System.currentTimeMillis();finished=true;}else Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
        }catch(Exception|AssertionError e){write(server,"p8-sensors-failure.json",Map.of("phase",phase,"tick",tick,"extra_tick",extraTick,"error",e.toString(),"walking",traces,"extras",extras));
            done=true;restore(server);LoggerFactory.getLogger("sifucraft").error("[sensor-test] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer server){
        if(level==null)return;cleanupActor();if(player!=null)player.closeContainer();
        for(var pos:originals.keySet())level.removeBlockEntity(pos);
        for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();
        for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();chunks.clear();
        if(clock!=null){server.clockManager().setTotalTicks(clock,originalTime);clock=null;}
    }
}
