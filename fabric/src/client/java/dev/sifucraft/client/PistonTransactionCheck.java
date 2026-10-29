package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.PistonTimeline;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.combat.EnemyMirrors;
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
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Isolated production path with real vanilla BEs and a separate process motion backend. */
final class PistonTransactionCheck {
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);setSilent(true);refreshDimensions();}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final List<Reference> references=new ArrayList<>();
    private final List<BlockPos> bases=new ArrayList<>();
    private final List<Vec3> starts=new ArrayList<>();
    private volatile boolean ready,finished;private long deadline;private ServerLevel level;private int stage;
    private volatile long presentationFrames;
    private final boolean pause=Boolean.getBoolean("sifucraft.pistonPauseSelfTest");
    private final java.nio.file.Path pauseFile=java.nio.file.Path.of(System.getProperty("sifucraft.motionPauseControl"));
    private volatile long pauseRequested,pauseAt,pausedTick;private volatile boolean resumed,pauseChecked;
    PistonTransactionCheck(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-piston-motion-check-"),"disposable save required");
        if(pause)PistonMachines.observePrepared((key,reply)->{
            if(pauseRequested!=0)return;pauseRequested=System.currentTimeMillis();pausedTick=level.getGameTime();pauseControl("1");
            LoggerFactory.getLogger("sifucraft").info("[piston-pause-test] requested actual world pause at prepared target={}",key);
        });
    }
    private void pauseControl(String value){try{java.nio.file.Files.writeString(pauseFile,value);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("piston transaction: "+why);}
    void clientTick(Minecraft mc,boolean gui){
        if(finished){mc.stop();return;}
        if(pauseRequested!=0&&mc.isPaused()&&pauseAt==0){pauseAt=System.currentTimeMillis();mc.getSingleplayerServer().execute(()->pausedTick=level.getGameTime());}
        if(pauseAt!=0&&!resumed&&System.currentTimeMillis()-pauseAt>=1200){
            resumed=true;mc.getSingleplayerServer().execute(()->{require(level.getGameTime()<=pausedTick+1,"world time froze during actual pause");pauseChecked=true;});pauseControl("0");
        }
        var view=WorldMotionWorld.current();ready=gui&&view!=null&&view.nativeStatus()!=null&&(view.nativeStatus().capabilities()&1)!=0&&view.shapesCurrent();
        if(mc.level!=null)for(var pos:PistonTimeline.positions())if(mc.level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston&&PistonTimeline.progress(piston)!=null){
            float progress=piston.getProgress(1);require(progress>=0&&progress<=1,"client presentation uses bounded server timeline");presentationFrames++;
        }
    }
    private void set(BlockPos pos,BlockState state){var original=level.getBlockState(pos);require(!original.hasBlockEntity(),"replaces no saved BE");originals.putIfAbsent(pos.immutable(),original);level.setBlock(pos,state,2);}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            if(stage==0){
                if(!ready)return;
                var view=WorldMotionWorld.current();if(view==null||!view.placement().map().equals("MotionFixture"))return;
                var a=EnemyMirrors.hitAttacker(101,1);var b=EnemyMirrors.hitAttacker(102,1);if(a==null||b==null)return;
                level=server.overworld();deadline=System.currentTimeMillis()+20000;
                for(var proxy:List.of(a,b)){
                    var base=proxy.blockPosition().west(2);bases.add(base);starts.add(proxy.position());
                    for(int i=-1;i<=4;i++)set(base.east(i),Blocks.AIR.defaultBlockState());
                    set(base,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.EAST));set(base.east(),Blocks.STONE.defaultBlockState());
                    var ref=new Reference(level);ref.setPos(proxy.position());ref.setOnGround(true);require(level.addFreshEntity(ref),"independent reference body registered");references.add(ref);
                    // The cleared cell is already recorded for restoration. Power
                    // must notify the piston, just as an ordinary placed block does.
                    level.setBlockAndUpdate(base.west(),Blocks.REDSTONE_BLOCK.defaultBlockState());
                }
                stage=1;LoggerFactory.getLogger("sifucraft").info("[piston-transaction-test] started two real vanilla piston lanes");return;
            }
            require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" stats="+PistonMachines.stats());
            if(stage==1){
                for(var base:bases)if(!level.getBlockState(base).getValue(BlockStateProperties.EXTENDED)||!level.getBlockState(base.east(2)).is(Blocks.STONE)||
                    level.getBlockEntity(base.east()) instanceof PistonMovingBlockEntity||level.getBlockEntity(base.east(2)) instanceof PistonMovingBlockEntity)return;
                if(PistonMachines.stats().pending()!=0||!ready)return;
                var results=new ArrayList<Map<String,Object>>();
                for(int i=0;i<2;i++){
                    var proxy=EnemyMirrors.hitAttacker(101+i,1);require(proxy!=null,"same activation retained");
                    var ref=references.get(i);var actual=proxy.position().subtract(starts.get(i));var reference=ref.position().subtract(starts.get(i));
                    require(proxy.position().distanceToSqr(ref.position())<1e-6,"Native receipts match independent actual vanilla body lane="+i+" proxy="+actual+" reference="+reference);
                    require(actual.x>.5&&Math.abs(actual.y)<1e-5&&Math.abs(actual.z)<1e-5,"full piston stroke moved physical endpoint");
                    results.add(Map.of("id",101+i,"actual",List.of(actual.x,actual.y,actual.z),"reference",List.of(reference.x,reference.y,reference.z)));
                }
                var stats=PistonMachines.stats();require(stats.prepared()>=4&&stats.applied()>=16&&stats.committed()>=4,"both half-steps used real production motion transactions: "+stats);
                if(pause){require(pauseAt!=0&&resumed&&pauseChecked&&stats.cancelled()>0&&stats.refundedSteps()>0,"actual pause interrupted the prepared flight, froze world time and returned confirmed unexecuted credits");require(stats.released()>=4&&stats.releaseFailed()==0,"both restarted half-steps completed real restore barriers");}
                else require(stats.released()==stats.prepared()&&stats.releaseFailed()==0,"every prepared target completed the new-geometry barrier and all original ticks: "+stats);
                require(presentationFrames>0,"client moving block retained its delayed server timeline");
                restore(server);stage=2;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-piston-transactions.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("result","PASS","backend","separate-process AABB simulator, not real Sifu physics","lanes",results,"prepared",stats.prepared(),"steps",stats.applied(),"committed",stats.committed(),"cancelled",stats.cancelled(),"released",stats.released(),"release_failed",stats.releaseFailed()),out));
                LoggerFactory.getLogger("sifucraft").info("[piston-transaction-test] PASS two physical lanes, real BE commits and Native geometry release barriers; {}",stats);
            }
            if(stage==2&&ready&&PistonMachines.stats().pending()==0)finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[piston-transaction-test] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});finished=true;}
    }
    void restore(MinecraftServer ignored){if(pause){PistonMachines.observePrepared(null);pauseControl("0");}for(var ref:references)ref.discard();references.clear();if(level!=null){for(var pos:originals.keySet())level.removeBlockEntity(pos);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);}originals.clear();}
}
