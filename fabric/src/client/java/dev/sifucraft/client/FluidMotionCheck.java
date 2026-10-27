package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.storage.AtomicFiles;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Original Cow/FloatGoal, independently integrated in a translated pool, against OS movement receipts. */
final class FluidMotionCheck implements FluidMotion.Observer {
    private static final class Reference extends Cow {
        private EntityDimensions size;
        Reference(ServerLevel level,FluidMotion.Sample sample){super(EntityTypes.COW,level);size=EntityDimensions.scalable(sample.width(),sample.height());refreshDimensions();setId(sample.entityId());getRandom().setSeed(sample.seed());setSilent(true);goalSelector.addGoal(0,new FloatGoal(this));setPos(sample.position().add(20,0,0));}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.84f):size;}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<EnemyFrame.Key,Reference> references=new HashMap<>();
    private final Map<EnemyFrame.Key,Vec3> starts=new HashMap<>();
    private final Map<EnemyFrame.Key,Integer> counts=new HashMap<>();
    private ServerLevel level;private volatile boolean finished;private int stage;private long started;private double maxPlanError,maxActualError;private int compared;
    FluidMotionCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-motion-check-"),"disposable save required");FluidMotion.observe(this);}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("fluid motion: "+why);}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private void set(BlockPos at,BlockState state){originals.putIfAbsent(at.immutable(),level.getBlockState(at));level.setBlockAndUpdate(at,state);}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null||!view.shapesCurrent()||!view.placement().map().equals("MotionFixture"))return;
            if(stage==0){
                level=server.overworld();started=System.currentTimeMillis();
                for(int offset:new int[]{0,20}){
                    // Enclosed, identical source-water pools; a real bubble column in the second lane.
                    for(int x=2;x<=7;x++)for(int z=-2;z<=5;z++)for(int y=63;y<=70;y++){
                        boolean floor=y==63,wall=x==2||x==7||z==-2||z==5;
                        set(new BlockPos(x+offset,y,z),(floor||wall?Blocks.STONE:Blocks.AIR).defaultBlockState());
                    }
                    set(new BlockPos(4+offset,63,2),Blocks.SOUL_SAND.defaultBlockState());
                }
                stage=10;LoggerFactory.getLogger("sifucraft").info("[fluid-motion-test] identical solid pools placed; waiting for collision ACK before adding medium");return;
            }
            require(System.currentTimeMillis()-started<45000,"deadline stats="+FluidMotion.stats()+" comparisons="+compared);
            if(stage==10){
                if(System.currentTimeMillis()-started<1500)return;
                for(int offset:new int[]{0,20})for(int x=3;x<=6;x++)for(int z=-1;z<=4;z++)for(int y=64;y<=68;y++)set(new BlockPos(x+offset,y,z),Blocks.WATER.defaultBlockState());
                stage=1;return;
            }
            if(stage==1){
                if(counts.getOrDefault(new EnemyFrame.Key(101,1),0)<35||counts.getOrDefault(new EnemyFrame.Key(102,1),0)<35)return;
                require(FluidMotion.stats().renewed()>0&&FluidMotion.stats().failed()==0,"continuous lease renewed without losing a target: "+FluidMotion.stats());
                for(var e:view.enemies().entries())if(starts.containsKey(e.key())){
                    var at=new Vec3(view.placement().mcX(e.location().x()),view.placement().mcY(e.location().z()-e.halfHeight()),view.placement().mcZ(e.location().y()));
                    require(at.y-starts.get(e.key()).y>.15,"actual OS endpoint rose under original FloatGoal/bubble integration target="+e.key()+" delta="+at.subtract(starts.get(e.key())));
                    require(e.health()==60,"water/bubble did not invent damage");
                }
                FluidMotion.observe(null);for(var entry:originals.entrySet())if(!level.getFluidState(entry.getKey()).isEmpty())level.setBlockAndUpdate(entry.getKey(),Blocks.AIR.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                if(FluidMotion.stats().active()!=0||!view.shapesCurrent())return;
                var stats=FluidMotion.stats();require(stats.released()==2&&stats.failed()==0,"both actual endpoints fell to support and restored original ticks: "+stats);
                var contacts=dev.sifucraft.combat.WorldEffects.stats();require(contacts.pathSegments()>0&&contacts.pathDropped()==0,"actual receipts reached environment collection without stale paths: "+contacts);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-fluid-motion.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","OS AABB fake, not UE capsule","compared_ticks",compared,"max_plan_error",maxPlanError,"max_actual_error",maxActualError,"stats",stats,"contacts",contacts),out));
                LoggerFactory.getLogger("sifucraft").info("[fluid-motion-test] PASS {} original ticks, plan error={}, physical endpoint error={}, {}",compared,maxPlanError,maxActualError,stats);restore(server);stage=3;return;
            }
            if(stage==3&&view.shapesCurrent())finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[fluid-motion-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    @Override public void prepared(FluidMotion.Sample sample){require(!references.containsKey(sample.key()),"no silent reacquisition/reset of original reference");references.put(sample.key(),new Reference(level,sample));starts.put(sample.key(),sample.position());}
    @Override public void simulated(FluidMotion.Sample sample){
        var ref=references.get(sample.key());ref.tick();double error=ref.position().subtract(20,0,0).distanceTo(sample.position());
        // Native coordinates cross the wire as float centimetres, while the
        // independent Cow retains double metres across ticks. Allow 0.01cm of
        // quantization; the actual endpoint comparison below is still separate.
        maxPlanError=Math.max(maxPlanError,error);require(error<1e-4&&ref.getDeltaMovement().distanceTo(sample.velocity())<1e-5,"original Cow physics differs at tick="+sample.tick()+" target="+sample.key()+" delta="+ref.position().subtract(20,0,0).subtract(sample.position()));
    }
    @Override public void completed(FluidMotion.Sample sample){
        var ref=references.get(sample.key());double error=ref.position().subtract(20,0,0).distanceTo(sample.position());maxActualError=Math.max(maxActualError,error);
        require(error<.001,"actual independent endpoint differs from original Cow by "+error+"m");counts.merge(sample.key(),1,Integer::sum);compared++;
    }
    void restore(MinecraftServer ignored){FluidMotion.observe(null);if(level!=null)for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());originals.clear();for(var r:references.values())r.discard();references.clear();}
}
