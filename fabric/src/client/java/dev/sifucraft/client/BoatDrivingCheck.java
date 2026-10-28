package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.*;
import dev.sifucraft.client.mixin.KeyMappingAccessor;
import dev.sifucraft.combat.*;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Real LocalPlayer keyboard, original boat physics, one actual native passenger and F8 handoffs. */
final class BoatDrivingCheck implements FluidMotion.Observer {
    private static final class LandingReference extends net.minecraft.world.entity.animal.cow.Cow {
        private EntityDimensions size;
        LandingReference(ServerLevel level,FluidMotion.Sample sample){super(EntityTypes.COW,level);size=EntityDimensions.scalable(sample.width(),sample.height());refreshDimensions();getRandom().setSeed(sample.seed());goalSelector.addGoal(0,new net.minecraft.world.entity.ai.goal.FloatGoal(this));setSilent(true);setId(sample.entityId());setPos(sample.position());setDeltaMovement(sample.velocity());setOnGround(sample.grounded());}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.8f):size;}
    }
    private final boolean bubble=Boolean.getBoolean("sifucraft.boatBubbleSelfTest");
    private final boolean bubbleDown=System.getProperty("sifucraft.boatBubbleDirection","up").equals("down");
    private final boolean piston=Boolean.getBoolean("sifucraft.boatPistonSelfTest");
    private final boolean pistonPlayer=Boolean.getBoolean("sifucraft.boatPistonPlayer");
    private final boolean pistonCancel=Boolean.getBoolean("sifucraft.boatPistonCancel");
    private final String pistonFault=System.getProperty("sifucraft.boatPistonFault","none");
    private volatile long faultPrepared,pauseAt,pausedWorldTick;
    private volatile boolean faultReleased,pauseChecked;
    private WorldMotionRequests.Scope endedScope;
    private WorldMotionRequests endedRequests;
    private EnemyProxy endedProxy;
    private long faultSteps;
    private boolean disconnected,sessionCleaned;
    private final java.nio.file.Path pauseFile=java.nio.file.Path.of(System.getProperty("sifucraft.motionPauseControl"));
    private boolean cancellationQueued;
    private final boolean pistonEast=System.getProperty("sifucraft.boatPistonDirection","west").equals("east");
    private BoatPistonFixture pistonFixture;
    private final boolean explosion=Boolean.getBoolean("sifucraft.boatExplosionSelfTest");
    private BoatExplosionFixture explosionFixture;
    private boolean explosionDrained;
    private long approachNs;private double approached;
    private double bubbleBoatY,bubbleSeatOffset,bubbleBoatRise,bubbleNativeRise,bubbleLag;
    private LandingReference landingReference;private float landingReferenceHp,landingLoss;private int landingTicks,landingCompleted;
    private static final java.nio.file.Path CONTROL=java.nio.file.Path.of(System.getProperty("sifucraft.boatDrivingControl"));
    private static void control(String value){try{java.nio.file.Files.writeString(CONTROL,value);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}
    private static String control(){try{return java.nio.file.Files.readString(CONTROL).trim();}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final EnumSet<CameraType> cameras=EnumSet.noneOf(CameraType.class);
    private volatile int stage;private volatile boolean finished,ready,release;
    private volatile SifuFrame frame;private volatile double driven;private volatile float clientHp;
    private volatile AssertionError failure;
    private ServerLevel level;private ServerPlayer player;private AbstractBoat boat;private EnemyFrame.Entry enemy;
    private Vec3 original,driveOrigin;private float yaw,pitch,mcHp,nativeHp;private CameraType camera;
    private long began,deadline;private int ticks,clientTicks,lastTick=-1;private boolean armed;
    private float driveYaw;private double npcMoved;
    private BlockPos basin;private boolean boatAdded;
    private net.minecraft.world.level.ChunkPos warmCenter;private java.util.concurrent.CompletableFuture<?> warmChunks;
    BoatDrivingCheck(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-boat-driving-check-"),"disposable world required");control("starting");if(bubble||explosion)FluidMotion.observe(this);
        require(!pistonCancel||piston,"cancellation requires piston fixture");
        require(Set.of("none","timeout","pause","session").contains(pistonFault)&&(!pistonFault.equals("none")?piston&&!pistonCancel:true),"valid mechanical fault scenario");
        if(!pistonFault.equals("none"))PistonMachines.preparedGate(()->faultReleased);
        if(pistonCancel||!pistonFault.equals("none"))PistonMachines.observePrepared((key,binding)->{
            if(enemy!=null&&key.equals(enemy.key())){
                if(pistonCancel&&!cancellationQueued){cancellationQueued=true;pistonFixture.interrupt();}
                if(!pistonFault.equals("none")&&faultPrepared==0){
                    faultPrepared=System.currentTimeMillis();pausedWorldTick=level.getGameTime();
                    if(pistonFault.equals("pause"))pauseControl("1");
                    if(pistonFault.equals("session")){
                        var view=WorldMotionWorld.current();endedScope=view.scope();endedRequests=view.requests();endedProxy=EnemyMirrors.hitAttacker(key.id(),key.spawn());
                        faultSteps=PistonMachines.stats().applied();control("session_restart");
                    }
                    pistonFixture.log("prepared fault="+pistonFault);
                }
            }
        });
    }
    private void pauseControl(String value){try{java.nio.file.Files.writeString(pauseFile,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    @Override public void prepared(FluidMotion.Sample sample){if(explosionFixture!=null)explosionFixture.prepared(sample);if(!bubble||enemy==null||!sample.key().equals(enemy.key())||stage<9)return;require(landingReference==null,"one physical landing continuation");landingReference=new LandingReference(level,sample);landingReferenceHp=landingReference.getHealth();}
    @Override public void simulated(FluidMotion.Sample sample){if(explosionFixture!=null)explosionFixture.simulated(sample);if(landingReference==null||!sample.key().equals(enemy.key()))return;level.tickNonPassenger(landingReference);landingTicks++;landingLoss=landingReferenceHp-landingReference.getHealth();require(sample.position().distanceTo(landingReference.position())<.15,"original independent landing trajectory");}
    @Override public void completed(FluidMotion.Sample sample){if(explosionFixture!=null)explosionFixture.completed(sample);if(landingReference!=null&&sample.key().equals(enemy.key()))landingCompleted++;}
    @Override public void beforeSimulation(FluidMotion.Sample sample,VanillaFluidBody body){if(explosionFixture!=null)explosionFixture.beforeSimulation(sample,body);}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("boat driving: "+why);}
    private static void key(InputInjector input,KeyMapping binding,boolean down){var code=((KeyMappingAccessor)binding).sifucraft$key();require(code.getType()==com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,"keyboard binding");input.key(code.getValue(),down?InputInjector.PRESS:InputInjector.RELEASE,0);}
    void clientTick(Minecraft mc,InputInjector input,SifuFrame snapshot,boolean anchored){
        if(failure!=null)throw failure;
        frame=snapshot;if(release){input.releaseAll();release=false;}
        if(finished){input.releaseAll();if(camera!=null)mc.options.setCameraType(camera);mc.stop();return;}
        var v=WorldMotionWorld.current();ready=anchored&&mc.player!=null&&mc.gui.screen()==null&&snapshot!=null&&snapshot.minecraftOwnsInput()&&v!=null&&v.shapesCurrent()&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0;
        if(faultPrepared!=0&&!faultReleased){
            if(pistonFault.equals("timeout")&&System.currentTimeMillis()-faultPrepared>=700)faultReleased=true;
            if(pistonFault.equals("pause")){
                if(pauseAt==0&&mc.isPaused()&&v!=null&&v.nativeStatus()!=null&&(v.nativeStatus().flags()&WorldMotionProtocol.PAUSED)!=0){pauseAt=System.currentTimeMillis();pausedWorldTick=level.getGameTime();}
                if(pauseAt!=0&&System.currentTimeMillis()-pauseAt>=3200){
                    require(level.getGameTime()<=pausedWorldTick+1,"actual pause freezes boat world time");pauseChecked=true;faultReleased=true;pauseControl("0");
                }
            }
        }
        if(mc.player==null)return;clientHp=mc.player.getHealth();
        if((piston||explosion)&&stage==0&&ready&&v.placement().map().equals("Hideout_1_Main")&&approached<4&&
            v.enemies()!=null&&v.enemies().entries().stream().noneMatch(e->e.alive()&&e.maxHealth()==60)){
            long ns=System.nanoTime();double dt=approachNs==0?0:Math.min(.05,(ns-approachNs)/1e9);approachNs=ns;
            var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));approached+=mc.player.position().distanceTo(before);
        }else approachNs=0;
        if(stage!=3&&stage!=6&&stage!=7)return;
        if(stage==7){if(mc.player.isPassenger()){key(input,mc.options.keyShift,true);}else{input.releaseAll();stage=8;}return;}
        if(!ready)return;
        if(!(mc.player.getVehicle() instanceof AbstractBoat)){require(!armed,"actual client remains mounted after input handoff");return;} // Wait for the server's mount packet before the first input sample.
        if(!armed){input.releaseAll();if(camera==null)camera=mc.options.getCameraType();driveOrigin=mc.player.getVehicle().position();key(input,mc.options.keyUp,true);armed=true;clientTicks=0;lastTick=-1;}
        if(lastTick==mc.player.tickCount)return;lastTick=mc.player.tickCount;clientTicks++;
        require(clientHp==mcHp&&!mc.player.getAbilities().flying,"survival HP and movement");
        driven=Math.max(driven,mc.player.getVehicle().position().subtract(driveOrigin).horizontalDistance());cameras.add(mc.options.getCameraType());
        if(stage==3){
            if(clientTicks==20||clientTicks==40||clientTicks==60){key(input,mc.options.keyTogglePerspective,true);key(input,mc.options.keyTogglePerspective,false);}
            if(clientTicks==30)key(input,mc.options.keyLeft,true);if(clientTicks==40)key(input,mc.options.keyLeft,false);
            if(clientTicks<80)return;
            require(driven>.15&&cameras.size()==3,"actual keyboard propulsion and all three F5 cameras distance="+driven+" cameras="+cameras);
            input.releaseAll();armed=false;ticks=0;stage=4;
            control("native_ready");
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-driving-test] F8_NATIVE_READY actual player+NPC drove {}m cameras={}; switch to Native",driven,cameras);
        }else if(clientTicks>=20){input.releaseAll();armed=false;stage=7;}
    }
    private void put(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"no user BE replaced");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    void tick(MinecraftServer server){if(finished||failure!=null)return;try{
        if(began!=0)require(System.currentTimeMillis()-began<180000,"bounded complete fixture stage="+stage);var v=WorldMotionWorld.current();
        if(endedScope!=null){sessionTick(server,v);return;}
        if(stage==0){
            if(!ready||v==null||v.enemies()==null||v.placement().map().equals("MotionFixture"))return;
            level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();
            enemy=v.enemies().entries().stream().filter(e->e.alive()&&e.maxHealth()==60&&EnemyMirrors.hitAttacker(e.id(),e.spawn())!=null).findFirst().orElse(null);if(enemy==null)return;
            var proxy=EnemyMirrors.hitAttacker(enemy.id(),enemy.spawn());if(!SceneFluidGeometry.knownVolume(level,proxy.getBoundingBox().inflate(6)))return;
            if(began==0)began=System.currentTimeMillis();
            if(warmChunks==null){warmCenter=net.minecraft.world.level.ChunkPos.containing(proxy.blockPosition());warmChunks=level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED,warmCenter,3);return;}if(!warmChunks.isDone())return;
            original=player.position();yaw=player.getYRot();pitch=player.getXRot();mcHp=player.getHealth();nativeHp=frame.health();
            boat=(AbstractBoat)EntityTypes.OAK_BOAT.create(level,EntitySpawnReason.COMMAND);require(boat!=null,"original boat factory");boat.setPos(proxy.position().add(0,.4,0));
            boolean clear=false;for(var d:List.of(new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,0,1),new Vec3(0,0,-1))){boolean blocked=false;for(var shape:level.getBlockCollisions(boat,boat.getBoundingBox().expandTowards(d.scale(2)).deflate(.01)))if(!shape.isEmpty()){blocked=true;break;}if(!blocked){driveYaw=d.x>0?-90:d.x<0?90:d.z>0?0:180;clear=true;break;}}
            require(clear,"two metres of actual scene clearance");boat.setYRot(piston?0:driveYaw);
            basin=BlockPos.containing(boat.getX(),Math.floor(proxy.getY()+.05),boat.getZ());
            // Build and acknowledge the dry collision before acquiring a 500ms
            // NPC lease. A large synchronous fixture build must not starve renewal.
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=-1;y<=1;y++){
                var pos=basin.offset(x,y,z);put(pos,y==-1||Math.abs(x)==4||Math.abs(z)==4?Blocks.GLASS.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            deadline=System.currentTimeMillis()+20000;stage=1;return;
        }
        require(frame!=null&&frame.health()==nativeHp&&player.getHealth()==mcHp,"both player health authorities unchanged");
        require(System.currentTimeMillis()<(stage==4||stage==5?began+180000:deadline),"bounded stage="+stage+" passenger="+BoatPassengers.stats()+" fluid="+FluidMotion.stats());
        if(v==null||v.nativeStatus()==null)return; // A seqlock miss grants no work; keep the bounded fixture state.
        var e=v==null||v.enemies()==null?null:v.enemies().entries().stream().filter(x->x.key().equals(enemy.key())).findFirst().orElse(null);
        if(e!=null){require(e.alive()&&(bubble&&stage>=9||explosion&&stage>=9||e.health()==enemy.health()),"same native passenger activation/HP stage="+stage+" expected="+enemy.health()+" actual="+e.health());npcMoved=Math.max(npcMoved,Math.hypot(e.location().x()-enemy.location().x(),e.location().y()-enemy.location().y())/100);}
        if(stage==1){
            if(!boatAdded){
                if(!v.shapesCurrent()||v.geometry().index()!=WorldBlocks.index().revision()||v.nativeStatus().shapeRevision()!=v.geometry().revision()||
                   !SceneFluidGeometry.knownVolume(level,new net.minecraft.world.phys.AABB(basin).inflate(4,1,4)))return;
                int[] water={0};SceneBlockSupport.withScene(v.scene(),()->{
                    for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){var pos=basin.offset(x,0,z);if(SceneFluidGeometry.canOccupy(level,pos)){put(pos,Blocks.WATER.defaultBlockState());water[0]++;}}
                    return null;
                });
                require(water[0]>0,"acknowledged basin contains original-scene fluid cells");
                // Original boat placement starts at the water surface. Adding
                // water after the dry boat landed leaves it UNDER_WATER.
                boat.setPos(boat.getX(),basin.getY()+1,boat.getZ());require(level.addFreshEntity(boat),"one real boat placed onto water");boatAdded=true;return;
            }
            if(boat.getPassengers().size()!=1)return;require(boat.getFirstPassenger() instanceof EnemyProxy,"native acquisition precedes MC passenger link");
            ticks=0;stage=2;deadline=System.currentTimeMillis()+20000;return;
        }
        if(stage==2){
            require(BoatPassengers.holds(enemy.key())&&boat.getPassengers().size()==1,"native passenger remains attached during flotation warmup");
            if(ticks%20==0){var proxy=EnemyMirrors.hitAttacker(enemy.id(),enemy.spawn());org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-driving-test] flotation tick={} basin={} boat={} wet={} seats={} proxy={} eye={} air={} stats={}",ticks,basin,boat.position(),boat.isInWater(),boat.getPassengers().size(),proxy==null?null:proxy.position(),proxy==null?0:proxy.getEyeY(),proxy==null?0:proxy.getAirSupply(),BoatPassengers.stats());}
            if(++ticks<30||!ready||!boat.isInWater()||boat.getPassengers().size()!=1)return;
            if(explosion){explosionFixture=new BoatExplosionFixture(level,boat,e);stage=50;deadline=System.currentTimeMillis()+30000;return;}
            if(piston){
                if(pistonPlayer){player.teleportTo(level,boat.getX(),boat.getY()+.5,boat.getZ(),Set.of(),boat.getYRot(),0,true);require(player.startRiding(boat),"original player boards mechanical test boat");}
                stage=30;ticks=0;deadline=System.currentTimeMillis()+20000;return;
            }
            if(bubble){
                bubbleBoatY=boat.getY();var proxy=EnemyMirrors.hitAttacker(enemy.id(),enemy.spawn());bubbleSeatOffset=proxy.getY()-boat.getY();
                put(BlockPos.containing(boat.getX(),basin.getY()-1,boat.getZ()),(bubbleDown?Blocks.MAGMA_BLOCK:Blocks.SOUL_SAND).defaultBlockState());
                stage=20;ticks=0;deadline=System.currentTimeMillis()+20000;return;
            }
            player.teleportTo(level,boat.getX(),boat.getY()+.5,boat.getZ(),Set.of(),driveYaw,0,true);require(player.startRiding(boat),"original player boarding");
            require(boat.getFirstPassenger()==player&&boat.getPassengers().size()==2,"vanilla inserts player into driving seat");stage=3;deadline=System.currentTimeMillis()+20000;return;
        }
        if(stage==3||stage==6){require(boat.getPassengers().size()==2&&BoatPassengers.holds(enemy.key()),"player+actual NPC remain attached while driving");return;}
        if(stage==4){if(!control().equals("native_sent")||frame.minecraftOwnsInput())return;if(++ticks<20)return;require(BoatPassengers.holds(enemy.key())&&boat.getPassengers().size()==2,"F8 Native keeps the native passenger's boat relation");stage=5;ticks=0;control("mc_ready");org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-driving-test] F8_MC_READY Native interval passed; switch back to MC");return;}
        if(stage==5){if(!control().equals("mc_sent")||!ready)return;require(boat.getPassengers().size()==2,"F8 return keeps both seats");stage=6;deadline=System.currentTimeMillis()+20000;return;}
        if(stage==7)return;
        if(stage==50){
            if(!explosionDrained&&explosionFixture.canDrain()){
                for(var pos:List.copyOf(originals.keySet()))if(!level.getFluidState(pos).isEmpty())put(pos,Blocks.AIR.defaultBlockState());
                explosionDrained=true;
            }
            if(!ready||!explosionFixture.tick(v))return;boat.discard();stage=9;deadline=System.currentTimeMillis()+20000;return;
        }
        if(stage==30){
            if(++ticks<20||!BoatPassengers.seated(enemy.key()))return;
            if(pistonFixture==null){pistonFixture=new BoatPistonFixture(level,boat,enemy,pistonEast,this::put);return;}
            if(v.geometry().index()!=WorldBlocks.index().revision()||v.nativeStatus().shapeRevision()!=v.geometry().revision())return;
            if(pistonPlayer){var at=frame.playerLocation();if(Math.hypot(v.placement().mcX(at.x())-player.getX(),v.placement().mcZ(at.y())-player.getZ())>.15)return;}
            pistonFixture.power(true);stage=31;ticks=0;deadline=System.currentTimeMillis()+20000;return;
        }
        if(stage==31||stage==32){
            require(BoatPassengers.holds(enemy.key())&&boat.getPassengers().size()==(pistonPlayer?2:1),"piston carries its seated passengers without competing ownership");
            if(++ticks%10==0)pistonFixture.log("stage="+stage+" tick="+ticks);
            if(pistonCancel){if(!pistonFixture.cancelled())return;}
            else{
                if(!pistonFixture.completed(stage==31))return;
                if(stage==31&&!pistonFault.equals("none")){
                    require(faultPrepared!=0&&faultReleased&&PistonMachines.stats().cancelled()>0,"fault cancels old flight before completing a fresh one");
                    require(PistonMachines.stats().cancelRestoreFailed()==0&&PistonMachines.stats().cancelRestored()>0,"every acquired cancelled mechanical lease proves complete original restoration");
                    if(pistonFault.equals("pause"))require(pauseChecked,"actual native pause and frozen world observed");
                    pistonFixture.fault(pistonFault,pauseAt==0?0:System.currentTimeMillis()-pauseAt);
                }
                if(stage==31){pistonFixture.power(false);stage=32;ticks=0;deadline=System.currentTimeMillis()+20000;return;}
            }
            boat.discard();stage=9;ticks=0;deadline=System.currentTimeMillis()+20000;return;
        }
        if(stage==20){
            if(bubbleDown&&boat.getPassengers().isEmpty()){
                require(ticks>=40,"original downward bubble timer precedes ejection");boat.discard();stage=9;ticks=0;deadline=System.currentTimeMillis()+20000;return;
            }
            require(BoatPassengers.holds(enemy.key())&&boat.getPassengers().size()==1,"bubble retains native passenger authority");
            if(e!=null){double feet=v.placement().mcY(e.location().z()-e.halfHeight());bubbleBoatRise=Math.max(bubbleBoatRise,boat.getY()-bubbleBoatY);bubbleNativeRise=Math.max(bubbleNativeRise,feet-bubbleBoatY-bubbleSeatOffset);bubbleLag=Math.max(bubbleLag,Math.abs(feet-boat.getY()-bubbleSeatOffset));}
            if(++ticks%20==0)org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-bubble-test] tick={} boat_rise={} native_rise={} max_seat_lag={} stats={}",ticks,bubbleBoatRise,bubbleNativeRise,bubbleLag,BoatPassengers.stats());
            if(bubbleDown){require(ticks<180,"original downward bubble ejects its passenger");return;}
            if(ticks<100)return;
            require(bubbleBoatRise>.2&&bubbleNativeRise>bubbleBoatRise-.5&&bubbleLag<1,"original upward bubble impulse reaches the actual passenger at bounded seat lag");
            boat.discard();stage=9;ticks=0;deadline=System.currentTimeMillis()+20000;return;
        }
        if(stage==8){require(!player.isPassenger(),"real shift dismount reached server");boat.discard();stage=9;ticks=0;deadline=System.currentTimeMillis()+20000;return;}
        if(stage==9){if(BoatPassengers.stats().active()!=0)return;require(BoatPassengers.stats().failed()==0,"passenger handback has no failed lease");restore(server);stage=10;ticks=0;return;}
        if(stage==10){if(++ticks<25||v==null||!v.shapesCurrent()||v.geometry().index()!=WorldBlocks.index().revision()||v.geometry().motion()<ShapeChanges.revision()||v.nativeStatus().shapeRevision()!=v.geometry().revision()||BoatPassengers.stats().active()!=0||FluidMotion.stats().active()!=0||PistonMachines.stats().pending()!=0||v.requests().pending()!=0)return;
            if(explosion){
                explosionFixture.settled(v);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p11-native-boat-explosion.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"explosion",explosionFixture.report(),"passengers",BoatPassengers.stats()),out));
                FluidMotion.observe(null);finished=true;control("passed");return;
            }
            if(piston){
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve(pistonCancel?"p11-native-boat-piston-cancel.json":pistonFault.equals("none")?"p11-native-boat-piston.json":"p11-native-boat-piston-"+pistonFault+".json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"player_aboard",pistonPlayer,"stroke",pistonFixture.report(),"passengers",BoatPassengers.stats()),out));
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-piston-test] PASS {}, actual passenger lease resumed, handback and cleanup ACK",pistonCancel?"prepared source cancellation":"full extension/reference displacement/retraction");finished=true;control("passed");return;
            }
            if(bubble){
                require(landingTicks>1&&landingCompleted>0&&e!=null&&Math.abs(enemy.health()-e.health()-landingLoss*3)<.02,"original reference landing HP maps once to the actual NPC");
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p11-native-boat-bubble.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"direction",bubbleDown?"down":"up","boat_rise_m",bubbleBoatRise,"native_rise_m",bubbleNativeRise,"max_seat_lag_m",bubbleLag,"reference_landing_loss",landingLoss,"landing_ticks",landingTicks,"passengers",BoatPassengers.stats()),out));
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-bubble-test] PASS original {} bubble, actual NPC seat/ejection and reference landing, cleanup ACK",bubbleDown?"downward":"upward");FluidMotion.observe(null);if(landingReference!=null)landingReference.discard();finished=true;control("passed");return;
            }
            require(npcMoved>.1&&player.position().distanceTo(original)<.05,"actual NPC travelled and original player location restored");
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p11-actual-driving.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"actual_local_player",true,"native_npc",enemy.key(),"keyboard_distance_m",driven,"npc_distance_m",npcMoved,"f5_cameras",cameras.toString(),"f8_roundtrip",true,"native_hp",nativeHp,"passengers",BoatPassengers.stats()),out));
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-driving-test] PASS actual LocalPlayer keyboard/water/F5/F8/player+NPC/shift dismount, NPC={}m, cleanup native ACK",npcMoved);finished=true;
            control("passed");
        }
    }catch(Exception|AssertionError error){if(pistonFixture!=null)pistonFixture.log("FAIL stage="+stage);restore(server);org.slf4j.LoggerFactory.getLogger("sifucraft").error("[boat-driving-test] FAIL",error);failure=new AssertionError(error);}}
    private void sessionTick(MinecraftServer server,WorldMotionWorld.View view)throws java.io.IOException{
        require(PistonMachines.stats().applied()==faultSteps,"ended scope never executes a mechanical step");
        if(!disconnected){if(view!=null&&endedScope.equals(view.scope()))return;disconnected=true;stage=40;}
        if(!sessionCleaned){
            if(PistonMachines.stats().pending()!=0||BoatPassengers.stats().active()!=0||endedRequests.pending()!=0||!endedProxy.isRemoved())return;
            require(!boat.isRemoved()&&boat.getPassengers().stream().noneMatch(r->r instanceof EnemyProxy),"original boat survives Native loss without a stale passenger");
            var refused=endedRequests.request(endedScope,id->{throw new AssertionError("ended scope reached request factory");});
            require(refused.isCompletedExceptionally(),"old scope cannot enqueue after disconnect");
            restore(server);sessionCleaned=true;stage=41;control("session_cleaned");
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-piston-test] SESSION_CLEANED old native={} no pending motion/old proxy or seat",Long.toUnsignedString(endedScope.nativeSession()));
        }
        if(view==null||!ready||view.scope().nativeSession()==endedScope.nativeSession()||!view.shapesCurrent()||
           view.nativeStatus()==null||view.geometry().index()!=WorldBlocks.index().revision()||view.geometry().motion()<ShapeChanges.revision()||view.nativeStatus().shapeRevision()!=view.geometry().revision())return;
        require(BoatPassengers.stats().active()==0&&PistonMachines.stats().pending()==0,"new Native session has no old passenger or mechanical lease");
        if(view.requests().pending()!=0||FluidMotion.stats().active()!=0)return;
        var refused=view.requests().request(endedScope,id->{throw new AssertionError("old scope reached new session request factory");});
        require(refused.isCompletedExceptionally(),"new session refuses the old scope before transport");
        AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p11-native-boat-piston-session.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
            "passed",true,"old_scope",endedScope,"new_scope",view.scope(),"no_old_step",true,"old_proxy_removed",endedProxy.isRemoved(),"geometry_ack",view.geometry(),"passengers",BoatPassengers.stats(),"machines",PistonMachines.stats()),out));
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-piston-test] PASS real Native session ended during Prepared, old scope/seat/queues cleared, fresh collision ACK");
        finished=true;control("passed");
    }
    void restore(MinecraftServer ignored){
        if(explosionFixture!=null)explosionFixture.close();
        release=true;if(pistonCancel||!pistonFault.equals("none")){PistonMachines.observePrepared(null);PistonMachines.preparedGate(null);if(pistonFault.equals("pause"))pauseControl("0");}if(pistonFixture!=null)pistonFixture.close();
        if(player!=null&&original!=null){player.stopRiding();player.teleportTo(level,original.x,original.y,original.z,Set.of(),yaw,pitch,true);player.setDeltaMovement(Vec3.ZERO);}
        if(boat!=null&&!boat.isRemoved())boat.discard();
        if(level!=null){
            // Head cells are recorded before a piston creates its BE. Failure
            // halfway through a stroke must also remove that moving collision.
            for(var pos:originals.keySet()){
                level.removeBlockEntity(pos);
                if(!level.getFluidState(pos).isEmpty())level.setBlock(pos,Blocks.AIR.defaultBlockState(),18);
            }
            for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),18);
            originals.clear();
            if(warmCenter!=null){level.getChunkSource().removeTicketWithRadius(TicketType.FORCED,warmCenter,3);warmCenter=null;}
        }
        if(ignored!=null&&!ignored.isRunning()){FluidMotion.observe(null);if(landingReference!=null)landingReference.discard();}
    }
}
