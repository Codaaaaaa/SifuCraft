package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.blocks.NativeMotionSubjects;
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

/** Disposable real-game fixture. It never fabricates a Native position or a motion command. */
final class NativePistonCheck {
    private static final class Reference extends Cow {
        double peakY;
        Reference(ServerLevel level,float width,float height,boolean physics){super(EntityTypes.COW,level);this.width=width;this.height=height;setNoAi(!physics);setNoGravity(!physics);setSilent(true);refreshDimensions();}
        private float width=.7f,height=1.84f;
        @Override protected void registerGoals(){}
        @Override public void tick(){super.tick();peakY=Math.max(peakY,getY());}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(width>0?width:.7f,height>0?height:1.84f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean ready,finished;private volatile int stage;private volatile long began;
    private double walked;private long lastNs;private float playerHealth;
    private ServerLevel level;private EnemyFrame.Entry target;private Vec3 start,end,referenceStart;private BlockPos base;private Direction direction;
    private Reference reference;private long deadline,settledAt,lastMonitor;
    private final boolean pause=Boolean.getBoolean("sifucraft.pistonPauseSelfTest");
    private final boolean player=Boolean.getBoolean("sifucraft.nativePlayerPistonSelfTest");
    private final boolean lift=Boolean.getBoolean("sifucraft.nativePlayerPistonLift");
    private final boolean slime=Boolean.getBoolean("sifucraft.nativePlayerPistonSlime");
    private double nativePeak,referencePeak;private boolean nativeApex;
    private final java.nio.file.Path f8File=java.nio.file.Path.of(System.getProperty("sifucraft.playerPistonF8Control"));
    private volatile SifuFrame playerFrame;private volatile boolean mcReady;
    private BlockPos liftPayload;private Vec3 originalPlayer,setupPosition;private long platformSettled;
    private final java.nio.file.Path pauseFile=java.nio.file.Path.of(System.getProperty("sifucraft.motionPauseControl"));
    private volatile long pauseRequested,pauseAt,pausedTick;private volatile boolean resumed,pauseChecked;
    NativePistonCheck(){require(DevWorld.LEVEL_ID.startsWith(player?"sifucraft-player-piston-check-":"sifucraft-piston-native-check-"),"disposable save required");
        require(!lift||player,"lift diagnostic requires player mode");if(lift)stage=-3;
        require(!slime||lift,"slime diagnostic requires the elevated player fixture");
        if(slime)dev.sifucraft.combat.FluidMotion.observe(new dev.sifucraft.combat.FluidMotion.Observer(){
            public void prepared(dev.sifucraft.combat.FluidMotion.Sample value){}
            public void simulated(dev.sifucraft.combat.FluidMotion.Sample value){}
            public void completed(dev.sifucraft.combat.FluidMotion.Sample value){if(NativeMotionSubjects.player(value.key())){nativePeak=Math.max(nativePeak,value.position().y);if(start!=null&&nativePeak-start.y>1&&value.velocity().y<=0)nativeApex=true;}}
        });
        PistonMachines.observePrepared((key,binding)->{
            if(target==null||!target.key().equals(key)||reference!=null)return;
            var offset=binding.rootOffset();var proxy=NativeMotionSubjects.find(key);require(proxy!=null,"same binding body");
            referenceStart=proxy.position().add(offset.x()/100.0,offset.z()/100.0+target.halfHeight()/100.0-binding.rootHalfHeight()/100.0,offset.y()/100.0);
            reference=new Reference(level,binding.rootRadius()*2/100,binding.rootHalfHeight()*2/100,slime);reference.setPos(referenceStart);reference.setOnGround(true);require(level.addFreshEntity(reference),"independent actual root-sized vanilla body registered");
            LoggerFactory.getLogger("sifucraft").info("[native-piston-test] physical binding root_radius={} root_half={} root_offset_cm={} reference_start={}",binding.rootRadius(),binding.rootHalfHeight(),offset,referenceStart);
            if(pause&&pauseRequested==0){pauseRequested=System.currentTimeMillis();pausedTick=level.getGameTime();pauseControl("1");LoggerFactory.getLogger("sifucraft").info("[native-piston-test] pause requested while physical target is prepared");}
        });
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("native piston: "+why);}
    private void pauseControl(String value){try{java.nio.file.Files.writeString(pauseFile,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    private void f8Control(String value){try{java.nio.file.Files.writeString(f8File,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    void clientTick(Minecraft mc,boolean gui,SifuFrame frame){
        playerFrame=frame;mcReady=gui;
        if(finished){mc.stop();return;}
        // The first MC anchor is needed before WorldMotionWorld can exist.
        // Seed ordinary F8 here rather than waiting on that dependent view.
        if(lift&&stage==-3&&mc.player!=null&&frame!=null&&!frame.minecraftOwnsInput())f8Control("1:mc");
        if(lift&&stage<0&&frame!=null&&frame.minecraftOwnsInput()){
            try{if(java.nio.file.Files.exists(f8File)&&java.nio.file.Files.readString(f8File).equals("1:mc"))f8Control("0:idle");}
            catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        }
        if(pauseRequested!=0&&mc.isPaused()&&pauseAt==0){pauseAt=System.currentTimeMillis();mc.getSingleplayerServer().execute(()->pausedTick=level.getGameTime());}
        if(pauseAt!=0&&!resumed&&System.currentTimeMillis()-pauseAt>=1200){resumed=true;mc.getSingleplayerServer().execute(()->{require(level.getGameTime()<=pausedTick+1,"actual Native pause froze MC world time");pauseChecked=true;});pauseControl("0");}
        var view=WorldMotionWorld.current();ready=view!=null&&view.shapesCurrent()&&view.nativeStatus()!=null&&(view.nativeStatus().capabilities()&1)!=0&&view.nativeStatus().flags()==0&&
            (player?frame!=null&&(lift&&(stage<0||stage>=5)||!frame.minecraftOwnsInput()&&(view.nativeStatus().capabilities()&dev.sifucraft.bridge.WorldMotionProtocol.PLAYER_MACHINE_CAPABILITY)!=0):gui);
        long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;
        if(!ready||mc.player==null||frame==null)return;
        require(view.placement().map().equals("Hideout_1_Main"),"known isolated checkpoint map");
        if(began==0){began=System.currentTimeMillis();playerHealth=frame.health();}
        require(frame.health()==playerHealth,"no change to Native player HP during fixture");
        if(stage<3)require(System.currentTimeMillis()-began<90000,"bounded live fixture deadline stage="+stage);
        // The known idle fixture is just beyond the 30m table radius at the entry.
        // Walk at 1m/s through ordinary MC collision; stop as soon as a body appears.
        if(!player&&stage==0&&view.enemies().entries().isEmpty()&&walked<4){
            var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);
        }
    }
    private void set(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity(),"does not replace saved BE");originals.putIfAbsent(pos.immutable(),old);level.setBlock(pos,state,2);}
    void tick(MinecraftServer server){
        if(stage==4){finished=true;return;}
        if(finished||(!ready&&stage!=3&&stage<5&&!(lift&&stage==-3)))return;
        try{
            var view=WorldMotionWorld.current();if(view==null)return;
            if(lift&&stage<0){prepareLift(server,view);return;}
            if(lift&&stage>=5){finishLift(server,view);return;}
            if(stage==0){
                level=server.overworld();
                var pc=player?NativeMotionSubjects.entry(view,NativeMotionSubjects.PLAYER):null;
                for(var candidate:player?(pc==null?java.util.List.<EnemyFrame.Entry>of():java.util.List.of(pc)):view.enemies().entries()){
                    if(!candidate.alive()||(!player&&candidate.health()!=60)||candidate.radius()!=35||candidate.velocity().x()!=0||candidate.velocity().y()!=0||candidate.velocity().z()!=0)continue;
                    var proxy=NativeMotionSubjects.find(candidate.key());if(proxy==null)continue;
                    if(lift){target=candidate;start=proxy.position();base=liftPayload.below();direction=Direction.UP;stage=1;deadline=System.currentTimeMillis()+20000;
                        LoggerFactory.getLogger("sifucraft").info("[native-piston-test] raised player fixture start={} base={} direction=up slime={}",start,base,slime);return;}
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
            var proxy=NativeMotionSubjects.find(target.key());require(proxy!=null,"same activation retained");
            if(stage==1){var power=direction==Direction.UP?base.west():base.relative(direction,-1);originals.putIfAbsent(power,level.getBlockState(power));level.setBlockAndUpdate(power,Blocks.REDSTONE_BLOCK.defaultBlockState());stage=2;return;}
            if(stage==2){
                var destination=base.relative(direction,2);
                if(!level.getBlockState(base).getValue(BlockStateProperties.EXTENDED)||!level.getBlockState(destination).is(lift?(slime?Blocks.SLIME_BLOCK:Blocks.HONEY_BLOCK):Blocks.STONE)||level.getBlockEntity(destination) instanceof PistonMovingBlockEntity||PistonMachines.stats().pending()!=0)return;
                require(reference!=null,"physical binding produced the independent reference");
                if(slime){
                    var physics=dev.sifucraft.combat.FluidMotion.stats();require(physics.failed()==0,"player impulse lease remains valid: "+physics);
                    if(!nativeApex||reference.getDeltaMovement().y>0)return;
                    require(Math.abs(nativePeak-reference.peakY)<.03,"player passive apex agrees with the independent zero-input vanilla body: native="+nativePeak+" reference="+reference.peakY);
                    referencePeak=reference.peakY;
                    require(physics.acquired()>0&&physics.steps()>0&&PistonMachines.stats().releaseFailed()==0,"real player impulse continued beyond mechanical contact");
                    end=proxy.position();stage=5;deadline=System.currentTimeMillis()+20000;f8Control("3:mc");return;
                }
                require(proxy.position().subtract(start).distanceToSqr(reference.position().subtract(referenceStart))<.0009,"actual Native delta matches ordinary vanilla root body: native="+proxy.position().subtract(start)+" reference="+reference.position().subtract(referenceStart));
                require(proxy.position().distanceToSqr(start)>.01,"real target moved");
                var stats=PistonMachines.stats();require(stats.prepared()>=2&&stats.releaseFailed()==0&&(pause?stats.released()>=2:stats.released()==stats.prepared()),"every completed physical half-step restored all three original ticks: "+stats);
                if(pause)require(pauseAt!=0&&resumed&&pauseChecked,"real Native pause and resumed world time were observed");
                end=proxy.position();settledAt=System.currentTimeMillis();stage=3;
            }
            if(stage==3){
                if(proxy instanceof dev.sifucraft.combat.EnemyProxy enemy)require(enemy.getHealth()==target.health()/3,"Native HP unchanged");require(proxy.position().distanceToSqr(end)<.0009,"no post-release pullback");
                if(System.currentTimeMillis()-settledAt<3000)return;
                if(lift){stage=5;deadline=System.currentTimeMillis()+20000;f8Control("3:mc");return;}
                complete(server);
            }else if(stage==4)finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[native-piston-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    private Vec3 nativeFeet(WorldMotionWorld.View view,SifuFrame frame){var p=frame.playerLocation();return new Vec3(view.placement().mcX(p.x()),view.placement().mcY(p.z()-frame.capsuleHalfHeight()),view.placement().mcZ(p.y()));}
    private void prepareLift(MinecraftServer server,WorldMotionWorld.View view){
        var frame=playerFrame;if(frame==null)return;
        if(stage==-3){
            if(view.nativeStatus()==null||view.nativeStatus().flags()!=0||!view.placement().map().equals("Hideout_1_Main")||!frame.hasPawn())return;
            if(!frame.minecraftOwnsInput()){f8Control("1:mc");return;}
            if(!mcReady)return;level=server.overworld();var backing=server.getPlayerList().getPlayers().getFirst();originalPlayer=backing.position();
            for(int[] offset:new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}}){
                var trial=new BlockPos((int)Math.floor(originalPlayer.x)+offset[0],(int)Math.ceil(originalPlayer.y)+1,(int)Math.floor(originalPlayer.z)+offset[1]);
                boolean clear=true;for(var p:new BlockPos[]{trial.below(),trial.below().west(),trial,trial.above(),trial.above(2),trial.above(3)})
                    if(!level.getBlockState(p).isAir()||!SceneBlockSupport.nativeShape(level,p).isEmpty()){clear=false;break;}
                if(clear){liftPayload=trial;break;}
            }
            require(liftPayload!=null,"empty elevated player platform and headroom");
            set(liftPayload.below(),Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.UP));set(liftPayload,(slime?Blocks.SLIME_BLOCK:Blocks.HONEY_BLOCK).defaultBlockState());
            setupPosition=Vec3.atBottomCenterOf(liftPayload).add(0,slime?1:.9375,0);
            backing.teleportTo(level,setupPosition.x,setupPosition.y+.15,setupPosition.z,java.util.Set.of(),backing.getYRot(),backing.getXRot(),true);
            stage=-2;deadline=System.currentTimeMillis()+20000;
            LoggerFactory.getLogger("sifucraft").info("[native-piston-test] ordinary MC setup platform={} standing={} original={}",liftPayload,setupPosition,originalPlayer);return;
        }
        require(System.currentTimeMillis()<deadline,"bounded platform handover stage="+stage);
        if(stage==-2){
            if(!frame.minecraftOwnsInput()||!mcReady||!view.shapesCurrent()||nativeFeet(view,frame).distanceToSqr(setupPosition)>.0009){platformSettled=0;return;}
            long dwell=Math.max(300,Math.min(15000,Long.getLong("sifucraft.nativePlayerPistonDwellMs",300L)));
            if(platformSettled==0)platformSettled=System.currentTimeMillis();if(System.currentTimeMillis()-platformSettled<dwell)return;
            stage=-1;f8Control("2:native");return;
        }
        if(!frame.minecraftOwnsInput()&&NativeMotionSubjects.playerReady(view))stage=0;
    }
    private void finishLift(MinecraftServer server,WorldMotionWorld.View view)throws java.io.IOException{
        require(System.currentTimeMillis()<deadline,"bounded ordinary MC return stage="+stage);
        var frame=playerFrame;if(frame==null)return;
        if(stage==5){
            if(!frame.minecraftOwnsInput()||!mcReady)return;var backing=server.getPlayerList().getPlayers().getFirst();
            if(slime){var physics=dev.sifucraft.combat.FluidMotion.stats();require(physics.failed()==0,"F8 player physics handoff confirmed: "+physics);if(physics.active()!=0||physics.released()==0)return;}
            backing.teleportTo(level,originalPlayer.x,originalPlayer.y,originalPlayer.z,java.util.Set.of(),backing.getYRot(),backing.getXRot(),true);stage=6;return;
        }
        if(stage==6){if(!frame.minecraftOwnsInput()||nativeFeet(view,frame).distanceToSqr(originalPlayer)>.0009)return;restore(server);stage=7;return;}
        if(stage==7){if(!view.shapesCurrent())return;stage=8;settledAt=0;f8Control("4:native");return;}
        if(frame.minecraftOwnsInput())return;
        require(frame.health()==playerHealth,"Native HP unchanged after actual final handback");
        require(nativeFeet(view,frame).distanceToSqr(originalPlayer)<.0009,"no movement after final native floor restoration");
        if(settledAt==0)settledAt=System.currentTimeMillis();
        if(System.currentTimeMillis()-settledAt>=3000)complete(server);
    }
    private void complete(MinecraftServer server)throws java.io.IOException{
        var stats=PistonMachines.stats();restore(server);stage=4;
        var report=new LinkedHashMap<String,Object>();report.put("result","PASS");report.put("target",target.id());report.put("spawn",target.spawn());report.put("start",start.toString());report.put("end",end.toString());
        report.put("prepared",stats.prepared());report.put("steps",stats.applied());report.put("released",stats.released());report.put("restored_ticks",player?1:7);report.put("post_release_ms",slime?0:3000);
        report.put("direction",direction.toString());report.put("payload",lift?(slime?"slime":"honey"):"stone");report.put("native_player_hp",playerHealth);report.put("ordinary_mc_setup_and_return",lift);
        if(lift)report.put("final_native_handback_stable_ms",3000);
        if(slime){report.put("native_peak_y",nativePeak);report.put("reference_peak_y",referencePeak);report.put("fluid_stats",dev.sifucraft.combat.FluidMotion.stats().toString());report.put("handoff_at_apex",true);}
        AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve(player?"p8-native-player-piston.json":"p8-native-piston.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
        LoggerFactory.getLogger("sifucraft").info("[native-piston-test] PASS real actor, vanilla reference, geometry barriers, required movement handback; slime_apex_handoff={} stats={}",slime,stats);
    }
    void restore(MinecraftServer ignored){PistonMachines.observePrepared(null);if(slime)dev.sifucraft.combat.FluidMotion.observe(null);if(pause)pauseControl("0");if(lift&&stage!=6)f8Control("0:done");if(reference!=null)reference.discard();reference=null;if(level!=null){for(var pos:originals.keySet())level.removeBlockEntity(pos);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);}originals.clear();}
}
