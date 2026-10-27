package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonActionTickets;
import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Real scheduled repeater clocks. Fixed capacity, increasing active count, then one contained flood. */
final class CircuitScaleCheck {
    private static final int[] COUNTS={0,1,8,32,64,64,0};
    private static final String[] NAMES={"baseline","active1","active8","active32","active64","water64","quiet"};
    private static final int[][] WIRES={{0,0},{2,0},{3,0},{3,2},{3,3},{1,3},{0,3},{0,1},{4,2}};
    private final boolean live=Boolean.getBoolean("sifucraft.circuitScaleLive");
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final Set<ChunkPos> chunks=new HashSet<>(),forced=new HashSet<>();private final Set<UUID> initialItems=new HashSet<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();private final List<Double> duties=new ArrayList<>(),intervals=new ArrayList<>();
    private final List<Double> renderIntervals=new ArrayList<>(),ackSpans=new ArrayList<>();private final int[] edges=new int[64],steadyEdges=new int[64];private final boolean[] extended=new boolean[64];
    private volatile SifuFrame frame;private volatile boolean ready,atObservation,returned,finished;private volatile int stage,phase,ticks;
    private ServerLevel level;private ServerPlayer player;private BlockPos origin,observation;private Vec3 original;private float yaw,pitch,nativeHealth,health;private MobEffectInstance oldSlow;
    private long deadline,beganTick,lastTick,lastRender,phaseAt,pendingAt;private volatile long revision;private long superseded,stableAt;private int previousCount,movingSamples;private boolean restored;
    private dev.sifucraft.bridge.WorldMotionRequests.Scope scope;
    private volatile int fpsCap;private volatile String throttle="unobserved";
    private volatile double cameraDeviation,positionDeviation;private volatile int shapeBytes,shapeBoxes;
    private static final java.nio.file.Path CONTROL=java.nio.file.Path.of("../../build/p8-native-player-f8-control.txt"),PHASE=java.nio.file.Path.of("../../build/p8-circuit-scale-phase.txt");
    CircuitScaleCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-circuit-scale-check-"),"disposable save required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("circuit scale: "+why);}
    private static void write(java.nio.file.Path p,String value){try{java.nio.file.Files.writeString(p,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    void beginTick(MinecraftServer ignored){beganTick=System.nanoTime();}
    void clientTick(Minecraft mc,SifuFrame snapshot,BlockShapes.Metrics metrics){
        frame=snapshot;if(finished){mc.stop();return;}
        // Keep an unattended benchmark out of vanilla's 60-second AFK cap. This
        // only marks activity; the user's FPS limit and input routing stay intact.
        if(stage>0&&stage<=5)mc.getFramerateLimitTracker().onInputReceived();
        fpsCap=mc.getFramerateLimitTracker().getFramerateLimit();throttle=mc.getFramerateLimitTracker().getThrottleReason().name();
        if(live&&stage==0&&mc.player!=null&&snapshot!=null&&!snapshot.minecraftOwnsInput())write(CONTROL,"1:mc");
        if(live&&stage<3&&snapshot!=null&&snapshot.minecraftOwnsInput()){try{if(java.nio.file.Files.exists(CONTROL)&&java.nio.file.Files.readString(CONTROL).equals("1:mc"))write(CONTROL,"0:idle");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
        var view=WorldMotionWorld.current();ready=view!=null&&view.shapesCurrent()&&SifuCollision.active()!=null&&(live?view.placement().map().equals("Hideout_1_Main"):view.placement().map().equals("MotionFixture"));
        atObservation=mc.player!=null&&observation!=null&&mc.player.position().distanceTo(Vec3.atBottomCenterOf(observation))<.1&&mc.player.onGround();
        returned=mc.player!=null&&original!=null&&mc.player.position().distanceTo(original)<.03;
        if(stage==5&&ticks>80&&mc.player!=null){
            cameraDeviation=Math.max(cameraDeviation,Math.max(Math.abs(Math.IEEEremainder(mc.player.getYRot(),360)),Math.abs(mc.player.getXRot()-30)));
            positionDeviation=Math.max(positionDeviation,mc.player.position().distanceTo(Vec3.atBottomCenterOf(observation)));
            shapeBytes=Math.max(shapeBytes,metrics.payloadBytes());shapeBoxes=Math.max(shapeBoxes,metrics.boxes());
        }
        long now=System.nanoTime();synchronized(renderIntervals){if(stage==5&&ticks>80&&lastRender!=0)renderIntervals.add((now-lastRender)/1e6);lastRender=now;}
        synchronized(ackSpans){if(stage==5&&ticks>80&&view!=null){long next=view.geometry().revision();if(next!=revision){if(pendingAt!=0)superseded++;revision=next;}if(!view.shapesCurrent()){if(pendingAt==0)pendingAt=now;}else if(pendingAt!=0){ackSpans.add((now-pendingAt)/1e6);pendingAt=0;}}}
    }
    private BlockPos p(int i,int x,int y,int z){return origin.offset((i%8)*8+x,y,(i/8)*8+z);}
    private void put(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity()||originals.containsKey(pos),"no saved BE replaced");require(SifuCollision.active().shapeAt(pos).isEmpty(),"fixture cell is clear of Native geometry "+pos);originals.putIfAbsent(pos.immutable(),old);level.setBlockAndUpdate(pos,state);}
    private BlockState repeater(Direction input){return Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,input).setValue(BlockStateProperties.DELAY,4);}
    private void build(){for(int i=0;i<64;i++){
        for(var xy:WIRES)put(p(i,xy[0],-1,xy[1]),Blocks.STONE.defaultBlockState());
        for(var xy:new int[][]{{1,0},{3,1},{2,3},{0,2},{5,2},{5,3},{5,4}})put(p(i,xy[0],-1,xy[1]),Blocks.STONE.defaultBlockState());
        for(var xy:WIRES)put(p(i,xy[0],0,xy[1]),Blocks.REDSTONE_WIRE.defaultBlockState());
        put(p(i,3,0,1),repeater(Direction.NORTH));put(p(i,2,0,3),repeater(Direction.EAST));put(p(i,0,0,2),repeater(Direction.SOUTH));put(p(i,1,0,0),Blocks.AIR.defaultBlockState());
        put(p(i,4,-1,2),Blocks.REDSTONE_LAMP.defaultBlockState());put(p(i,5,0,2),Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH));put(p(i,5,0,3),Blocks.STONE.defaultBlockState());
    }
        for(int x=0;x<=5;x++)for(int z=0;z<=4;z++)put(p(0,x,-1,z),Blocks.STONE.defaultBlockState());
        put(p(0,4,-1,2),Blocks.REDSTONE_LAMP.defaultBlockState());
        for(int x=-1;x<=6;x++)for(int z=-1;z<=5;z++)if(x==-1||x==6||z==-1||z==5)for(int y=0;y<=3;y++)put(p(0,x,y,z),Blocks.GLASS.defaultBlockState());
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)put(observation.offset(x,-1,z),Blocks.GLASS.defaultBlockState());
    }
    private void teleport(Vec3 at,float targetYaw,float targetPitch){player.teleportTo(level,at.x,at.y,at.z,java.util.Set.of(),targetYaw,targetPitch,true);}
    private BlockState seedRest(int i){return(i==0?Blocks.GLASS:Blocks.AIR).defaultBlockState();}
    private void startPhase(){
        int wanted=COUNTS[phase];for(int i=previousCount;i<wanted;i++){put(p(i,1,0,0),repeater(Direction.WEST));put(p(i,-1,0,0),Blocks.REDSTONE_BLOCK.defaultBlockState());}
        if(wanted==0)for(int i=0;i<64;i++)put(p(i,1,0,0),Blocks.AIR.defaultBlockState());
        if(phase==5){for(int x=0;x<=5;x++)for(int z=0;z<=4;z++)for(int y=0;y<=3;y++){var at=p(0,x,y,z);originals.putIfAbsent(at,level.getBlockState(at));}put(p(0,1,1,1),Blocks.WATER.defaultBlockState());}
        ticks=0;phaseAt=System.nanoTime();lastTick=0;duties.clear();intervals.clear();movingSamples=0;cameraDeviation=positionDeviation=0;shapeBytes=shapeBoxes=0;
        synchronized(renderIntervals){renderIntervals.clear();lastRender=0;}synchronized(ackSpans){ackSpans.clear();pendingAt=0;superseded=0;}
        write(PHASE,NAMES[phase]);LoggerFactory.getLogger("sifucraft").info("[circuit-scale-test] phase={} active={} game_time={} fixed_machines=64",NAMES[phase],wanted,level.getGameTime());
    }
    private void machineSample(){for(int i=0;i<64;i++){
        var state=level.getBlockState(p(i,5,0,2));boolean now=state.is(Blocks.STICKY_PISTON)&&state.getValue(BlockStateProperties.EXTENDED);if(now!=extended[i]){edges[i]++;extended[i]=now;}
        int stones=0;for(int z=2;z<=4;z++){var at=p(i,5,0,z);if(level.getBlockState(at).is(Blocks.STONE))stones++;if(level.getBlockEntity(at) instanceof PistonMovingBlockEntity moving){movingSamples++;if(moving.getMovedState().is(Blocks.STONE))stones++;}}
        require(stones==1,"one original payload including moving BE machine="+i+" stones="+stones);
    }}
    private static Map<String,Object> stats(List<Double> values){if(values.isEmpty())return Map.of("n",0);var sorted=values.stream().mapToDouble(Double::doubleValue).sorted().toArray();return Map.of("n",sorted.length,"mean_ms",values.stream().mapToDouble(Double::doubleValue).average().orElse(0),"p50_ms",sorted[sorted.length/2],"p95_ms",sorted[(int)Math.floor((sorted.length-1)*.95)],"max_ms",sorted[sorted.length-1]);}
    private void phaseResult(){int wanted=COUNTS[phase];int minimum=Integer.MAX_VALUE;for(int i=0;i<64;i++){int changed=edges[i]-steadyEdges[i];if(i<wanted&&!(phase==5&&i==0)){require(changed>=4,"actual closed clock must keep extending/retracting machine="+i+" phase="+NAMES[phase]+" transitions="+changed);minimum=Math.min(minimum,changed);}if(wanted==0)require(changed==0&&!extended[i],"quiet clock drained without hidden activity machine="+i);}
        if(phase==5){require(!level.getBlockState(p(0,0,0,0)).is(Blocks.REDSTONE_WIRE),"contained water actually washes the clock");long dust=0,repeaters=0;for(var entity:level.getAllEntities())if(entity instanceof ItemEntity item&&!initialItems.contains(item.getUUID())){if(item.getItem().is(net.minecraft.world.item.Items.REDSTONE))dust+=item.getItem().getCount();if(item.getItem().is(net.minecraft.world.item.Items.REPEATER))repeaters+=item.getItem().getCount();}require(dust==9&&repeaters==4,"washed circuit produces exactly original items dust="+dust+" repeaters="+repeaters);}
        var row=new LinkedHashMap<String,Object>();row.put("phase",NAMES[phase]);row.put("active",wanted);row.put("world_ticks",ticks);row.put("warmup_ticks",80);row.put("phase_wall_ms",(System.nanoTime()-phaseAt)/1e6);row.put("server_tick_before_probe",stats(duties));row.put("tick_intervals",stats(intervals));row.put("estimated_tps",intervals.isEmpty()?0:1000/intervals.stream().mapToDouble(Double::doubleValue).average().orElse(1));row.put("minimum_steady_piston_transitions",minimum==Integer.MAX_VALUE?0:minimum);row.put("moving_be_samples",movingSamples);row.put("minecraft_fps_cap",fpsCap);row.put("minecraft_throttle",throttle);synchronized(renderIntervals){row.put("mc_render_intervals",stats(renderIntervals));}synchronized(ackSpans){row.put("shape_pending_spans",stats(ackSpans));row.put("revisions_changed_while_pending",superseded);row.put("shape_pending_open_ms",pendingAt==0?0:(System.nanoTime()-pendingAt)/1e6);}row.put("maximum_camera_deviation_degrees",cameraDeviation);row.put("maximum_position_deviation_m",positionDeviation);row.put("maximum_shape_payload_bytes",shapeBytes);row.put("maximum_shape_boxes",shapeBoxes);rows.add(row);LoggerFactory.getLogger("sifucraft").info("[circuit-scale-test] measured {}",row);
    }
    void tick(MinecraftServer server){if(finished)return;try{
        var snapshot=frame;if(snapshot==null)return;
        if(stage==0){if(!ready||!snapshot.minecraftOwnsInput()||server.getPlayerList().getPlayers().isEmpty())return;var view=WorldMotionWorld.current();if(live)require(view.enemies().entries().stream().noneMatch(e->e.alive()),"quiet Native entry without active NPCs");level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();original=player.position();yaw=player.getYRot();pitch=player.getXRot();health=player.getHealth();nativeHealth=snapshot.health();scope=view.scope();deadline=System.currentTimeMillis()+360000;
            var slow=player.getEffect(MobEffects.SLOW_FALLING);oldSlow=slow==null?null:new MobEffectInstance(slow);player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,20000));origin=BlockPos.containing(original).offset(4,40,4);observation=origin.offset(24,10,-8);
            for(int x=-1;x<=64;x++)for(int z=-10;z<=63;z++){var at=origin.offset(x,0,z);var c=new ChunkPos(at.getX()>>4,at.getZ()>>4);if(chunks.add(c)&&level.setChunkForced(c.x(),c.z(),true))forced.add(c);}for(var e:level.getAllEntities())if(e instanceof ItemEntity)initialItems.add(e.getUUID());stage=1;return;
        }
        require(System.currentTimeMillis()<deadline,"bounded fixture stage="+stage+" phase="+phase);require(player.isAlive()&&!player.isRemoved(),"original player alive");if(live)require(snapshot.health()==nativeHealth,"Native health unchanged");var view=WorldMotionWorld.current();if(view!=null)require(scope.equals(view.scope()),"same scene during fixture");
        if(stage==1){if(chunks.stream().anyMatch(c->!level.isPositionEntityTicking(new BlockPos(c.x()*16+8,origin.getY(),c.z()*16+8))))return;build();stage=2;return;}
        if(stage==2){if(!ready)return;teleport(Vec3.atBottomCenterOf(observation),0,30);stage=3;return;}
        if(stage==3){if(!atObservation||!ready)return;stage=5;startPhase();return;}
        if(stage==5){long now=System.nanoTime();ticks++;if(ticks==2)for(int i=previousCount;i<COUNTS[phase];i++)put(p(i,-1,0,0),seedRest(i));machineSample();if(ticks==80)System.arraycopy(edges,0,steadyEdges,0,64);if(ticks>80){duties.add((now-beganTick)/1e6);if(lastTick!=0)intervals.add((now-lastTick)/1e6);}lastTick=now;if(ticks<400)return;phaseResult();previousCount=COUNTS[phase];if(++phase<COUNTS.length){startPhase();return;}require(PistonMachines.stats().pending()==0&&PistonActionTickets.stats().groups()==0,"all machine flights and action tickets drained");restore(server);stage=6;return;}
        if(stage==6){if(!returned||!ready)return;var actual=view.placement();double error=new Vec3(actual.mcX(snapshot.playerLocation().x()),actual.mcY(snapshot.playerLocation().z()-snapshot.capsuleHalfHeight()),actual.mcZ(snapshot.playerLocation().y())).distanceTo(original);if(error>.03)return;if(live){write(CONTROL,"2:native");stableAt=0;stage=7;return;}finish(server);return;}
        if(stage==7){if(snapshot.minecraftOwnsInput())return;if(stableAt==0)stableAt=System.currentTimeMillis();if(System.currentTimeMillis()-stableAt>=3000)finish(server);}
    }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[circuit-scale-test] FAIL",e);restore(server);finished=true;write(CONTROL,"0:done");Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}}
    private void finish(MinecraftServer server){try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-p9-circuit-scale.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","actual_ue",live,"fixed_machines",64,"rows",rows),out));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}write(CONTROL,"0:done");write(PHASE,"done");finished=true;LoggerFactory.getLogger("sifucraft").info("[circuit-scale-test] PASS 64 real clocks/pistons, 7 phases, contained water drops, queues drained, restored; live={}",live);}
    void restore(MinecraftServer ignored){if(restored)return;restored=true;if(level==null)return;if(player!=null){teleport(original,yaw,pitch);player.setHealth(health);player.removeEffect(MobEffects.SLOW_FALLING);if(oldSlow!=null)player.addEffect(oldSlow);}for(var e:level.getAllEntities())if(e instanceof ItemEntity&&!initialItems.contains(e.getUUID()))e.discard();for(var at:originals.keySet())level.removeBlockEntity(at);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();}
}
