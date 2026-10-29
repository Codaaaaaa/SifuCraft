package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.storage.AtomicFiles;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Explicit height matrix with original independent physics and actual OS endpoints. */
final class BubbleMatrixCheck implements FluidMotion.Observer {
    private static final class Reference extends Cow {
        private EntityDimensions size;
        Reference(ServerLevel level,FluidMotion.Sample sample){super(EntityTypes.COW,level);size=EntityDimensions.scalable(sample.width(),sample.height());refreshDimensions();setId(sample.entityId());getRandom().setSeed(sample.seed());setSilent(true);goalSelector.addGoal(0,new FloatGoal(this));setPos(sample.position().add(20,0,0));}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.84f):size;}
        @Override public boolean hurtServer(ServerLevel level,DamageSource source,float amount){return false;} // prediction bodies carry no damage authority
    }
    private final int height=Integer.getInteger("sifucraft.bubbleMatrixHeight",1);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<EnemyFrame.Key,Reference> references=new HashMap<>();
    private final Map<EnemyFrame.Key,Vec3> starts=new HashMap<>(),actuals=new HashMap<>();
    private final Map<EnemyFrame.Key,Integer> counts=new HashMap<>();
    private final Map<EnemyFrame.Key,Double> minima=new HashMap<>(),maxima=new HashMap<>();
    private final Map<EnemyFrame.Key,String> beforeStates=new HashMap<>();
    private ServerLevel level;private volatile boolean finished;private int stage,compared,drainY;private long began,phaseTick;private boolean statusGapChecked,pathGapChecked;
    private double planError,physicalError,velocityError;
    BubbleMatrixCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-bubble-matrix-check-")&&(height==1||height==4||height==16),"disposable save and explicit height required");FluidMotion.observe(this);}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("bubble matrix: "+why);}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private void set(BlockPos pos,BlockState state){originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void plane(int y,BlockState state){for(int offset:new int[]{0,20})for(int x=3;x<=6;x++)for(int z=-1;z<=4;z++)set(new BlockPos(x+offset,y,z),state);}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var v=WorldMotionWorld.current();if(v==null||v.nativeStatus()==null||!v.placement().map().equals("MotionFixture"))return;
            if(stage==0){
                if(!v.shapesCurrent()||EnemyMirrors.hitAttacker(101,1)==null||EnemyMirrors.hitAttacker(102,1)==null)return;
                level=server.overworld();began=System.currentTimeMillis();phaseTick=level.getGameTime();
                for(int id:new int[]{101,102}){var p=EnemyMirrors.hitAttacker(id,1);p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,4000));p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,4000));p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,4000,4));}
                for(int offset:new int[]{0,20}){
                    for(int x=2;x<=7;x++)for(int z=-2;z<=5;z++)for(int y=63;y<=height+74;y++)set(new BlockPos(x+offset,y,z),(y==63||x==2||x==7||z==-2||z==5?Blocks.STONE:Blocks.AIR).defaultBlockState());
                    for(int x=3;x<=6;x++)for(int z=-1;z<=4;z++)set(new BlockPos(x+offset,64,z),(z<2?Blocks.SOUL_SAND:Blocks.MAGMA_BLOCK).defaultBlockState());
                }
                stage=10;return;
            }
            require(System.currentTimeMillis()-began<160000,"deadline height="+height+" stage="+stage+" "+FluidMotion.stats());
            if(stage==10){
                if(!v.shapesCurrent()||level.getGameTime()-phaseTick<30)return;
                for(int y=65;y<65+height;y++)plane(y,Blocks.WATER.defaultBlockState());stage=1;return;
            }
            if(stage==1){
                if(!statusGapChecked&&counts.size()==2&&counts.values().stream().allMatch(n->n>=10)){
                    var before=FluidMotion.stats();int pending=v.requests().pending(),ticks=compared;
                    WorldMotionWorld.withUnavailableStatus(()->FluidMotion.tick(server));
                    require(before.equals(FluidMotion.stats())&&pending==v.requests().pending()&&ticks==compared,"one unavailable status must wait without commands, physics or reacquisition");
                    statusGapChecked=true;LoggerFactory.getLogger("sifucraft").info("[bubble-matrix-test] height={} unavailable status preserved both existing short leases without commands",height);
                }
                int needed=Math.max(60,height*8);if(counts.getOrDefault(new EnemyFrame.Key(101,1),0)<needed||counts.getOrDefault(new EnemyFrame.Key(102,1),0)<needed)return;
                var up=new EnemyFrame.Key(101,1);var down=new EnemyFrame.Key(102,1);
                require(maxima.get(up)>=65+height-.01,"actual upward endpoint reached the column top height="+height+" max="+maxima.get(up));
                if(height>1)require(minima.get(down)<starts.get(down).y-.1,"actual downward endpoint descended from its initial body height");
                require(level.getBlockState(new BlockPos(4,65,0)).is(Blocks.BUBBLE_COLUMN)&&!level.getBlockState(new BlockPos(4,65,0)).getValue(BubbleColumnBlock.DRAG_DOWN),"real upward base propagation");
                require(level.getBlockState(new BlockPos(4,65,2)).is(Blocks.BUBBLE_COLUMN)&&level.getBlockState(new BlockPos(4,65,2)).getValue(BubbleColumnBlock.DRAG_DOWN),"real downward base propagation");
                plane(64,Blocks.AIR.defaultBlockState());phaseTick=level.getGameTime();drainY=64+height;stage=2;
                LoggerFactory.getLogger("sifucraft").info("[bubble-matrix-test] height={} reached both directions/top, removed bases before controlled draining",height);return;
            }
            if(stage==2){
                if(level.getGameTime()-phaseTick<20||!v.shapesCurrent())return;
                for(var e:actuals.entrySet())if(e.getValue().y>drainY+1-.05)return; // keep a water cushion until the actual body follows
                plane(drainY,Blocks.AIR.defaultBlockState());drainY--;phaseTick=level.getGameTime();if(drainY<64)stage=3;return;
            }
            if(stage==3){
                if(FluidMotion.stats().active()!=0||v.requests().pending()!=0||!v.shapesCurrent())return;
                var stats=FluidMotion.stats();require(statusGapChecked&&pathGapChecked&&stats.acquired()==2&&stats.released()==2&&stats.failed()==0,"both continuous bodies restored all originals: "+stats);
                for(var e:references.entrySet()){require(Math.abs(actuals.get(e.getKey()).y-64)<.01,"actual body returned to supported floor");}
                require(WorldEffects.stats().sent()==0&&WorldEffects.stats().pathDropped()==0,"protected motion matrix adds no damage or lost physical path");
                var rows=new java.util.ArrayList<Map<String,Object>>();for(int id:new int[]{101,102}){var k=new EnemyFrame.Key(id,1);rows.add(Map.of("id",id,"direction",id==101?"up":"down","start",starts.get(k).toString(),"min_y",minima.get(k),"max_y",maxima.get(k),"ticks",counts.get(k)));}
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-bubble-matrix.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","height",height,"backend","independent-process AABB endpoint, not UE capsule","rows",rows,"compared_ticks",compared,"max_plan_error",planError,"max_physical_error",physicalError,"max_velocity_error",velocityError,"motion",stats),out));
                restore(server);stage=4;LoggerFactory.getLogger("sifucraft").info("[bubble-matrix-test] PASS height={} {} original physical ticks, plan={} endpoint={} velocity={} {}",height,compared,planError,physicalError,velocityError,stats);return;
            }
            if(stage==4&&v.shapesCurrent())finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[bubble-matrix-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    @Override public void prepared(FluidMotion.Sample sample){require(!references.containsKey(sample.key()),"same reference cannot silently restart");references.put(sample.key(),new Reference(level,sample));starts.put(sample.key(),sample.position());actuals.put(sample.key(),sample.position());minima.put(sample.key(),sample.position().y);maxima.put(sample.key(),sample.position().y);}
    private static long randomState(net.minecraft.util.RandomSource random){try{var field=random.getClass().getDeclaredField("seed");field.setAccessible(true);return ((java.util.concurrent.atomic.AtomicLong)field.get(random)).get();}catch(ReflectiveOperationException e){throw new AssertionError("diagnostic random layout "+random.getClass(),e);}}
    @Override public void beforeSimulation(FluidMotion.Sample sample,dev.sifucraft.combat.VanillaFluidBody body){var r=references.get(sample.key());long nativeRandom=randomState(body.getRandom()),refRandom=randomState(r.getRandom());String state="count="+counts.getOrDefault(sample.key(),0)+" native_ground="+sample.grounded()+" ref_ground="+r.onGround()+" native_pos="+sample.position()+" ref_pos="+r.position().subtract(20,0,0)+" velocity="+sample.velocity()+" native_random="+nativeRandom+" ref_random="+refRandom+" native_air="+body.getAirSupply()+" ref_air="+r.getAirSupply()+" native_water="+body.getFluidHeight(net.minecraft.tags.FluidTags.WATER)+" ref_water="+r.getFluidHeight(net.minecraft.tags.FluidTags.WATER);beforeStates.put(sample.key(),state);require(nativeRandom==refRandom,"random stream differs before tick target="+sample.key()+" "+state);}
    @Override public void simulated(FluidMotion.Sample sample){
        var r=references.get(sample.key());var before=r.position().subtract(20,0,0);var oldVelocity=r.getDeltaMovement();level.tickNonPassenger(r);
        require(r.tickCount==sample.entityTick(),"full original server tick differs target="+sample.key()+" native_entity_tick="+sample.entityTick()+" original_entity_tick="+r.tickCount);
        double p=r.position().subtract(20,0,0).distanceTo(sample.position()),v=r.getDeltaMovement().distanceTo(sample.velocity());planError=Math.max(planError,p);velocityError=Math.max(velocityError,v);
        require(p<1e-4&&v<1e-5,"original FloatGoal/travel differs height="+height+" target="+sample.key()+" pre="+beforeStates.get(sample.key())+" entity_tick="+r.tickCount+" world_tick="+sample.tick()+" before="+before+" old_v="+oldVelocity+" native_plan="+sample.position()+" native_v="+sample.velocity()+" ref="+r.position().subtract(20,0,0)+" ref_v="+r.getDeltaMovement()+" position="+p+" velocity="+v);
    }
    @Override public void completed(FluidMotion.Sample sample){
        var reference=references.get(sample.key());double error=reference.position().subtract(20,0,0).distanceTo(sample.position());physicalError=Math.max(physicalError,error);require(error<.001,"actual physical endpoint differs from original by "+error);
        double velocity=reference.getDeltaMovement().distanceTo(sample.velocity());velocityError=Math.max(velocityError,velocity);require(velocity<1e-5,"receipt changed original post-collision fluid forces target="+sample.key()+" original="+reference.getDeltaMovement()+" actual="+sample.velocity());
        if(!pathGapChecked&&sample.key().id()==101&&sample.position().distanceTo(starts.get(sample.key()))>.1){
            var before=WorldEffects.stats();var proxy=EnemyMirrors.hitAttacker(sample.key().id(),sample.key().spawn());
            require(before.pathSegments()>0,"actual physical contact path exists before status miss");
            WorldMotionWorld.withUnavailableStatus(()->WorldEffects.touch(proxy));
            require(before.equals(WorldEffects.stats()),"unavailable status neither executes nor discards a fresh actual contact path");pathGapChecked=true;
        }
        actuals.put(sample.key(),sample.position());minima.merge(sample.key(),sample.position().y,Math::min);maxima.merge(sample.key(),sample.position().y,Math::max);counts.merge(sample.key(),1,Integer::sum);compared++;
        // The next prediction starts at the external transport's actual pose.
        // Compare FIRST; retain the reference's own velocity, RNG and counters.
        // An unquantized free trajectory can cross water's 1mm threshold a tick
        // earlier after hundreds of float-centimetre wire round trips.
        reference.setPos(sample.position().add(20,0,0));
    }
    void restore(MinecraftServer ignored){FluidMotion.observe(null);if(level!=null){for(int id:new int[]{101,102}){var p=EnemyMirrors.hitAttacker(id,1);if(p!=null){p.removeEffect(MobEffects.FIRE_RESISTANCE);p.removeEffect(MobEffects.WATER_BREATHING);p.removeEffect(MobEffects.RESISTANCE);}}for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());}originals.clear();for(var r:references.values())r.discard();references.clear();}
}
