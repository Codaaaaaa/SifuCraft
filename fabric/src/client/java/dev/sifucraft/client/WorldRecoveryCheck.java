package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.combat.SceneExplosions;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

/** Separate real MC/Native processes. Vanilla freeze is explicit during the saved-state inspection. */
final class WorldRecoveryCheck {
    private final boolean verify=System.getProperty("sifucraft.worldRecoveryPhase","prepare").equals("verify");
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final Set<ChunkPos> forced=new HashSet<>();
    private final List<Entity> entities=new ArrayList<>();private final Set<UUID> expected=new HashSet<>(),exploded=new HashSet<>();
    private volatile SifuFrame frame;private volatile boolean finished;private boolean preserve;private ServerLevel level;
    private BlockPos origin,dispenser;private AbstractBoat boat;private long began=System.currentTimeMillis(),phaseAt,firstSession,savedTime;private int stage,duplicates;private float hp;
    private com.google.gson.JsonObject marker;
    WorldRecoveryCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-world-recovery-check-"),"disposable saved world");SceneExplosions.observe(id->{if(!exploded.add(id))duplicates++;});}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("world recovery: "+why);}
    void started(MinecraftServer server){if(verify)server.tickRateManager().setFrozen(true);}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished)mc.stop();}
    private Path path(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("p8-p11-world-recovery.json");}
    private void put(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity()||originals.containsKey(pos),"no saved BE replaced");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void force(){for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++){var c=new ChunkPos((origin.getX()>>4)+x,(origin.getZ()>>4)+z);level.setChunkForced(c.x(),c.z(),true);forced.add(c);}}
    private String save(Entity entity){var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());entity.saveWithoutId(out);return out.buildResult().toString();}
    private ItemStack cargo(){var item=new ItemStack(Items.DIAMOND,3);item.set(DataComponents.CUSTOM_NAME,Component.literal("P11 full-process cargo"));return item;}
    private void cargoCorrect(){var c=(Container)boat;require(ItemStack.matches(c.getItem(0),cargo())&&c.getItem(c.getContainerSize()-1).is(Items.APPLE)&&c.getItem(c.getContainerSize()-1).getCount()==7,"boat cargo components and counts");}
    private Container inventory(){return (Container)level.getBlockEntity(dispenser);}
    private void setup(){
        force();for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=0;y<=4;y++)put(origin.offset(x,y,z),(y==0||Math.abs(x)==3||Math.abs(z)==3?Blocks.OBSIDIAN:y<=3?Blocks.WATER:Blocks.AIR).defaultBlockState());
        for(int x=18;x<=22;x++)for(int z=-2;z<=2;z++)put(origin.offset(x,0,z),Blocks.OBSIDIAN.defaultBlockState());
        dispenser=origin.offset(0,3,-2);put(dispenser,Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.SOUTH));inventory().setItem(0,new ItemStack(Items.TNT));
        boat=(AbstractBoat)EntityTypes.OAK_CHEST_BOAT.create(level,EntitySpawnReason.COMMAND);require(boat!=null,"original chest boat");boat.setPos(Vec3.atBottomCenterOf(origin.offset(20,1,0)));((Container)boat).setItem(0,cargo());((Container)boat).setItem(((Container)boat).getContainerSize()-1,new ItemStack(Items.APPLE,7));require(level.addFreshEntity(boat),"register cargo boat");entities.add(boat);
    }
    private void prepare(MinecraftServer server,WorldMotionWorld.View view)throws Exception{
        if(stage==0){require(!Files.exists(path(server)),"fresh prepare world");firstSession=view.scope().nativeSession();var p=frame.playerLocation();origin=BlockPos.containing(view.placement().mcX(p.x())+24,view.placement().originY()+64,view.placement().mcZ(p.y())+8);hp=frame.health();setup();stage=1;phaseAt=level.getGameTime();return;}
        require(frame.health()==hp,"Native player HP remains unchanged");
        if(stage==1){if(level.getGameTime()-phaseAt<60||!view.shapesCurrent()||!SceneFluidGeometry.knownVolume(level,new AABB(origin).inflate(10)))return;
            for(int i=0;i<4;i++){var p=Vec3.atBottomCenterOf(origin.above(1));var tnt=new PrimedTnt(level,p.x+i*.03,p.y,p.z,null);require(level.addFreshEntity(tnt),"register original TNT");entities.add(tnt);expected.add(tnt.getUUID());}phaseAt=level.getGameTime();stage=2;return;
        }
        if(level.getGameTime()-phaseAt<20)return;require(exploded.isEmpty(),"save before explosion");
        // Use the real power edge: it creates vanilla's pending 4-tick action
        // and marks the chunk dirty through its actual TRIGGERED state change.
        put(dispenser.east(),Blocks.REDSTONE_BLOCK.defaultBlockState());
        require(level.getBlockTicks().hasScheduledTick(dispenser,Blocks.DISPENSER)&&inventory().getItem(0).getCount()==1,"actual redstone edge schedules one pending machine operation");
        server.tickRateManager().setFrozen(true);savedTime=level.getGameTime();cargoCorrect();
        var savedEntities=new LinkedHashMap<String,String>();for(var e:entities){if(e instanceof PrimedTnt tnt)require(tnt.getFuse()>0&&tnt.getFuse()<80,"save actual remaining original fuse");savedEntities.put(e.getUUID().toString(),save(e));}
        var before=new TreeMap<String,String>();var current=new TreeMap<String,String>();for(var e:originals.entrySet()){before.put(Long.toString(e.getKey().asLong()),BlockStateKey.encode(e.getValue()));current.put(Long.toString(e.getKey().asLong()),BlockStateKey.encode(level.getBlockState(e.getKey())));}
        var report=new LinkedHashMap<String,Object>();report.put("phase","prepared");report.put("native_session",Long.toUnsignedString(firstSession));report.put("origin",origin.asLong());report.put("dispenser",dispenser.asLong());report.put("world_time",savedTime);report.put("entities",savedEntities);report.put("originals",before);report.put("snapshot",current);report.put("boat",boat.getUUID().toString());report.put("native_hp",hp);
        AtomicFiles.write(path(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));preserve=true;require(server.saveEverything(false,true,true),"normal complete world save");finished=true;
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[world-recovery-test] PREPARED actual remaining TNT fuse/pose/velocity, water, pending dispenser stock and cargo boat; world frozen explicitly for exact save inspection");
    }
    private void verify(MinecraftServer server,WorldMotionWorld.View view)throws Exception{
        if(stage==0){
            marker=com.google.gson.JsonParser.parseString(Files.readString(path(server))).getAsJsonObject();require(marker.get("phase").getAsString().equals("prepared"),"separate JVM prepare marker");
            firstSession=Long.parseUnsignedLong(marker.get("native_session").getAsString());require(firstSession!=view.scope().nativeSession(),"separate actual Native process/session");
            origin=BlockPos.of(marker.get("origin").getAsLong());dispenser=BlockPos.of(marker.get("dispenser").getAsLong());savedTime=marker.get("world_time").getAsLong();hp=frame.health();
            for(var e:marker.getAsJsonObject("originals").entrySet())originals.put(BlockPos.of(Long.parseLong(e.getKey())),BlockStateKey.decode(e.getValue().getAsString()));force();stage=10;return;
        }
        require(frame.health()==hp,"Native player HP remains unchanged");
        if(stage==10){
            for(var e:marker.getAsJsonObject("entities").entrySet())if(level.getEntity(UUID.fromString(e.getKey()))==null)return;
            require(server.tickRateManager().isFrozen(),"explicit original frozen inspection");
            var differences=new ArrayList<Map<String,Object>>();
            for(var e:marker.getAsJsonObject("snapshot").entrySet()){
                var pos=BlockPos.of(Long.parseLong(e.getKey()));String actual=BlockStateKey.encode(level.getBlockState(pos)),wanted=e.getValue().getAsString();
                if(!actual.equals(wanted))differences.add(Map.of("pos",pos.toString(),"expected",wanted,"actual",actual));
            }
            if(!differences.isEmpty())AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-p11-recovery-mismatch.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("saved_time",savedTime,"loaded_time",level.getGameTime(),"differences",differences),out));
            require(differences.isEmpty(),"saved block/fluid mismatch: "+differences.stream().limit(8).toList());
            for(var e:marker.getAsJsonObject("entities").entrySet()){
                var actual=level.getEntity(UUID.fromString(e.getKey()));Entity reference=actual instanceof PrimedTnt?EntityTypes.TNT.create(level,EntitySpawnReason.LOAD):EntityTypes.OAK_CHEST_BOAT.create(level,EntitySpawnReason.LOAD);
                require(reference!=null,"original load reference");reference.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),TagParser.parseCompoundFully(e.getValue().getAsString())));
                require(actual.getUUID().equals(reference.getUUID())&&actual.position().equals(reference.position())&&actual.getDeltaMovement().equals(reference.getDeltaMovement()),"exact saved UUID/position/velocity before resuming");
                if(actual instanceof PrimedTnt tnt){require(tnt.getFuse()==((PrimedTnt)reference).getFuse(),"actual remaining fuse restored without refill");expected.add(tnt.getUUID());}else boat=(AbstractBoat)actual;
                entities.add(actual);reference.discard();
            }
            cargoCorrect();var stock=inventory().getItem(0);boolean scheduled=level.getBlockTicks().hasScheduledTick(dispenser,Blocks.DISPENSER);
            require(stock.is(Items.TNT)&&stock.getCount()==1&&scheduled,"pending dispenser: stock="+stock+" scheduled="+scheduled+" saved_time="+savedTime+" loaded_time="+level.getGameTime()+" frozen="+server.tickRateManager().isFrozen());
            require(level.getGameTime()>=savedTime,"monotonic saved game time");phaseAt=level.getGameTime();server.tickRateManager().setFrozen(false);stage=11;
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[world-recovery-test] exact full MC/Native restart restored four TNT, cargo boat, water and pending machine; resuming original world ticks");return;
        }
        if(stage==11){
            for(var entity:level.getAllEntities())if(entity instanceof PrimedTnt tnt&&expected.add(tnt.getUUID())){require(tnt.position().distanceToSqr(Vec3.atCenterOf(origin))<100,"only prepared dispenser creates additional TNT");entities.add(tnt);}
            if(level.getGameTime()-phaseAt<300)return;
            require(expected.size()==5&&exploded.equals(expected)&&duplicates==0,"four saved TNT plus one pending machine product explode once each");require(inventory().getItem(0).isEmpty(),"one saved machine item consumed once");require(level.getFluidState(origin.above(1)).isSource(),"saved cooling source survives resumed explosions");cargoCorrect();restore(server);stage=12;return;
        }
        if(!view.shapesCurrent())return;
        AtomicFiles.write(path(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("phase","verified","passed",true,"backend","separate real MC and Sifu processes; vanilla frozen inspection followed by actual resumed simulation","first_native",Long.toUnsignedString(firstSession),"next_native",Long.toUnsignedString(view.scope().nativeSession()),"saved_tnt",4,"pending_dispenser_tnt",1,"completed_explosions",exploded.size(),"boat_cargo_restored",true,"continued_world_ticks",300),out));
        finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[world-recovery-test] PASS full-process original TNT/state/pending machine/cargo persistence, five explosions exactly once, Native cleanup ACK");
    }
    void tick(MinecraftServer server){if(finished)return;try{
        require(System.currentTimeMillis()-began<300000,"bounded complete recovery test");var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null||view.nativeStatus().flags()!=0||frame==null||!frame.hasPawn()||view.placement().map().equals("MotionFixture")||!SceneFluidGeometry.available(server.overworld()))return;
        level=server.overworld();if(verify)verify(server,view);else prepare(server,view);
    }catch(Exception|AssertionError error){preserve=verify;if(!preserve)restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[world-recovery-test] FAIL; verification data retained for diagnosis",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}}
    void restore(MinecraftServer server){if(preserve)return;SceneExplosions.observe(null);server.tickRateManager().setFrozen(false);for(var e:entities)if(!e.isRemoved())e.discard();if(level!=null){if(dispenser!=null)level.removeBlockEntity(dispenser);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);for(var c:forced)level.setChunkForced(c.x(),c.z(),false);}originals.clear();forced.clear();}
}
