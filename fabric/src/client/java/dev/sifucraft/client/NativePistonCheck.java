package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Disposable real-game fixture. It never fabricates an NPC position or a motion command. */
final class NativePistonCheck {
    private static final class Reference extends Cow {
        Reference(ServerLevel level,float width,float height){super(EntityTypes.COW,level);this.width=width;this.height=height;setNoAi(true);setNoGravity(true);setSilent(true);refreshDimensions();}
        private float width=.7f,height=1.84f;
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(width>0?width:.7f,height>0?height:1.84f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean ready,finished;private volatile int stage;private volatile long began;
    private double walked;private long lastNs;private float playerHealth;
    private ServerLevel level;private EnemyFrame.Entry target;private Vec3 start,end,referenceStart;private BlockPos base;private Direction direction;
    private Reference reference;private long deadline,settledAt,lastMonitor;
    private final boolean pause=Boolean.getBoolean("sifucraft.pistonPauseSelfTest");
    private final java.nio.file.Path pauseFile=java.nio.file.Path.of(System.getProperty("sifucraft.motionPauseControl"));
    private volatile long pauseRequested,pauseAt,pausedTick;private volatile boolean resumed,pauseChecked;
    NativePistonCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-piston-native-check-"),"disposable save required");
        PistonMachines.observePrepared((key,binding)->{
            if(target==null||!target.key().equals(key)||reference!=null)return;
            var offset=binding.rootOffset();var proxy=EnemyMirrors.hitAttacker(key.id(),key.spawn());require(proxy!=null,"same binding body");
            referenceStart=proxy.position().add(offset.x()/100.0,offset.z()/100.0+target.halfHeight()/100.0-binding.rootHalfHeight()/100.0,offset.y()/100.0);
            reference=new Reference(level,binding.rootRadius()*2/100,binding.rootHalfHeight()*2/100);reference.setPos(referenceStart);reference.setOnGround(true);require(level.addFreshEntity(reference),"independent actual root-sized vanilla body registered");
            LoggerFactory.getLogger("sifucraft").info("[native-piston-test] physical binding root_radius={} root_half={} root_offset_cm={} reference_start={}",binding.rootRadius(),binding.rootHalfHeight(),offset,referenceStart);
            if(pause&&pauseRequested==0){pauseRequested=System.currentTimeMillis();pausedTick=level.getGameTime();pauseControl("1");LoggerFactory.getLogger("sifucraft").info("[native-piston-test] pause requested while physical target is prepared");}
        });
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("native piston: "+why);}
    private void pauseControl(String value){try{java.nio.file.Files.writeString(pauseFile,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    void clientTick(Minecraft mc,boolean gui,SifuFrame frame){
        if(finished){mc.stop();return;}
        if(pauseRequested!=0&&mc.isPaused()&&pauseAt==0){pauseAt=System.currentTimeMillis();mc.getSingleplayerServer().execute(()->pausedTick=level.getGameTime());}
        if(pauseAt!=0&&!resumed&&System.currentTimeMillis()-pauseAt>=1200){resumed=true;mc.getSingleplayerServer().execute(()->{require(level.getGameTime()<=pausedTick+1,"actual Native pause froze MC world time");pauseChecked=true;});pauseControl("0");}
        var view=WorldMotionWorld.current();ready=gui&&view!=null&&view.shapesCurrent()&&view.nativeStatus()!=null&&(view.nativeStatus().capabilities()&1)!=0&&view.nativeStatus().flags()==0;
        long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;
        if(!ready||mc.player==null||frame==null)return;
        require(view.placement().map().equals("Hideout_1_Main"),"known isolated checkpoint map");
        if(began==0){began=System.currentTimeMillis();playerHealth=frame.health();}
        require(frame.health()==playerHealth,"no change to Native player HP during fixture");
        if(stage<3)require(System.currentTimeMillis()-began<90000,"bounded live fixture deadline stage="+stage);
        // The known idle fixture is just beyond the 30m table radius at the entry.
        // Walk at 1m/s through ordinary MC collision; stop as soon as a body appears.
        if(stage==0&&view.enemies().entries().isEmpty()&&walked<4){
            var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);
        }
    }
    private void set(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity(),"does not replace saved BE");originals.putIfAbsent(pos.immutable(),old);level.setBlock(pos,state,2);}
    void tick(MinecraftServer server){
        if(stage==4){finished=true;return;}
        if(finished||(!ready&&stage!=3))return;
        try{
            var view=WorldMotionWorld.current();if(view==null)return;
            if(stage==0){
                level=server.overworld();
                for(var candidate:view.enemies().entries()){
                    if(!candidate.alive()||candidate.health()!=60||candidate.radius()!=35||candidate.velocity().x()!=0||candidate.velocity().y()!=0||candidate.velocity().z()!=0)continue;
                    var proxy=EnemyMirrors.hitAttacker(candidate.id(),candidate.spawn());if(proxy==null)continue;
                    for(var face:new Direction[]{Direction.EAST,Direction.WEST,Direction.SOUTH,Direction.NORTH}){
                        var floor=new BlockPos(proxy.blockPosition().getX(),(int)Math.ceil(proxy.getY()-1e-5),proxy.blockPosition().getZ());
                        var trial=floor.relative(face,-2);var payload=trial.relative(face);var destination=trial.relative(face,2);
                        if(proxy.getBoundingBox().intersects(new AABB(payload)))continue;
                        boolean clear=true;for(var pos:new BlockPos[]{trial,payload,destination,trial.relative(face,-1)})if(!level.getBlockState(pos).isAir()||SceneBlockSupport.intersects(level,net.minecraft.world.phys.shapes.Shapes.create(new AABB(pos)))){clear=false;break;}
                        if(!clear)continue;
                        target=candidate;start=proxy.position();base=trial;direction=face;
                        set(base,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,face));set(payload,Blocks.STONE.defaultBlockState());
                        stage=1;deadline=System.currentTimeMillis()+20000;
                        LoggerFactory.getLogger("sifucraft").info("[native-piston-test] fixture target={}/{} start={} base={} direction={} player_walked_m={}",candidate.id(),candidate.spawn(),start,base,face,walked);return;
                    }
                }
                return;
            }
            if(stage<3)require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" stats="+PistonMachines.stats());
            if(stage==3){
                long now=System.currentTimeMillis(),age=view.enemies()==null?Long.MAX_VALUE:now-view.enemies().receivedMs();
                if(view.nativeStatus()==null||view.nativeStatus().flags()!=0||age<0||age>200){settledAt=0;return;}
                if(settledAt==0||now-lastMonitor>200)settledAt=now;
                lastMonitor=now;
            }
            var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());require(proxy!=null,"same activation retained");
            if(stage==1){var power=base.relative(direction,-1);originals.putIfAbsent(power,level.getBlockState(power));level.setBlockAndUpdate(power,Blocks.REDSTONE_BLOCK.defaultBlockState());stage=2;return;}
            if(stage==2){
                var destination=base.relative(direction,2);
                if(!level.getBlockState(base).getValue(BlockStateProperties.EXTENDED)||!level.getBlockState(destination).is(Blocks.STONE)||level.getBlockEntity(destination) instanceof PistonMovingBlockEntity||PistonMachines.stats().pending()!=0)return;
                require(reference!=null,"physical binding produced the independent reference");
                require(proxy.position().subtract(start).distanceToSqr(reference.position().subtract(referenceStart))<.0009,"actual Native delta matches ordinary vanilla root body: native="+proxy.position().subtract(start)+" reference="+reference.position().subtract(referenceStart));
                require(proxy.position().distanceToSqr(start)>.01,"real target moved");
                var stats=PistonMachines.stats();require(stats.prepared()>=2&&stats.releaseFailed()==0&&(pause?stats.released()>=2:stats.released()==stats.prepared()),"every completed physical half-step restored all three original ticks: "+stats);
                if(pause)require(pauseAt!=0&&resumed&&pauseChecked,"real Native pause and resumed world time were observed");
                end=proxy.position();settledAt=System.currentTimeMillis();stage=3;
            }
            if(stage==3){
                require(proxy.getHealth()==target.health()/3,"Native HP unchanged");require(proxy.position().distanceToSqr(end)<.0009,"no post-release pullback");
                if(System.currentTimeMillis()-settledAt<3000)return;
                var stats=PistonMachines.stats();restore(server);stage=4;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-native-piston.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("result","PASS","target",target.id(),"spawn",target.spawn(),"start",start.toString(),"end",end.toString(),"prepared",stats.prepared(),"steps",stats.applied(),"released",stats.released(),"restored_ticks",7,"post_release_ms",3000),out));
                LoggerFactory.getLogger("sifucraft").info("[native-piston-test] PASS real actor, vanilla reference, geometry barriers, all original ticks and 3s no drift; {}",stats);
            }else if(stage==4)finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[native-piston-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){PistonMachines.observePrepared(null);if(pause)pauseControl("0");if(reference!=null)reference.discard();reference=null;if(level!=null){for(var pos:originals.keySet())level.removeBlockEntity(pos);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);}originals.clear();}
}
