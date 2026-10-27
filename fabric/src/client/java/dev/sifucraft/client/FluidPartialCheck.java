package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.storage.AtomicFiles;
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
import org.slf4j.LoggerFactory;

/** Extra physical geometry missing from scene metadata: real partial/busy burst receipts, then safe handback. */
final class FluidPartialCheck implements FluidMotion.Observer {
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);refreshDimensions();setSilent(true);setPos(24.5,64,.5);goalSelector.addGoal(0,new FloatGoal(this));}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private ServerLevel level;private Reference reference;private float referenceHp;private volatile boolean finished;private int stage;
    private long began,contactTick,setupIndex;private double maxActual=64,maxPlan=64;private int samples;
    FluidPartialCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-partial-check-"),"disposable save required");FluidMotion.observe(this);}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("fluid partial: "+why);}
    public void prepared(FluidMotion.Sample ignored){}
    public void simulated(FluidMotion.Sample value){if(value.key().id()==101)maxPlan=Math.max(maxPlan,value.position().y);}
    public void completed(FluidMotion.Sample value){if(value.key().id()==101){samples++;maxActual=Math.max(maxActual,value.position().y);require(value.position().y+value.height()<=67.0001,"no actual body crosses the unpublished physical ceiling");}}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private void set(BlockPos pos,BlockState state){originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null||!view.shapesCurrent()||!view.placement().map().equals("MotionFixture"))return;
            var proxy=EnemyMirrors.hitAttacker(101,1);if(proxy==null)return;
            if(stage==0){
                level=server.overworld();began=System.currentTimeMillis();
                for(int offset:new int[]{0,20})for(var pos:BlockPos.betweenClosed(new BlockPos(2+offset,63,-2),new BlockPos(6+offset,68,2))){
                    boolean wall=pos.getY()==63||pos.getX()==2+offset||pos.getX()==6+offset||Math.abs(pos.getZ())==2;
                    set(pos,wall?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                }
                set(new BlockPos(24,67,0),Blocks.STONE.defaultBlockState()); // reference knows its actual ceiling
                reference=new Reference(level);referenceHp=reference.getHealth();require(level.addFreshEntity(reference),"independent ordinary reference registered");setupIndex=WorldBlocks.index().revision();stage=1;return;
            }
            require(System.currentTimeMillis()-began<25000,"deadline stage="+stage+" stats="+FluidMotion.stats());
            require(FluidMotion.stats().failed()==0,"no partial/busy receipt cancels or restarts the physical body");
            if(stage==1){
                if(!view.geometry().current()||view.geometry().index()<setupIndex)return;
                for(int offset:new int[]{0,20})for(var pos:BlockPos.betweenClosed(new BlockPos(3+offset,64,-1),new BlockPos(5+offset,66,1)))set(pos,Blocks.WATER.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                if(FluidMotion.stats().blocked()==0||proxy.getY()<65.15)return;
                if(contactTick==0)contactTick=level.getGameTime();if(level.getGameTime()-contactTick<40)return;
                require(FluidMotion.stats().active()==1&&FluidMotion.stats().released()==0,"ceiling contact retains the same short renewed authority");
                require(Math.abs(proxy.getY()-65.16)<.001&&Math.abs(reference.getY()-65.16)<.04,"actual root and independent original body stop at their own true ceilings");
                require(maxPlan>65.17&&maxActual<=65.1601&&samples>5,"predictor encounters missing geometry but actual receipts remain bounded");
                for(int offset:new int[]{0,20})for(var pos:BlockPos.betweenClosed(new BlockPos(3+offset,64,-1),new BlockPos(5+offset,66,1)))set(pos,Blocks.AIR.defaultBlockState());stage=3;return;
            }
            if(stage==3){
                if(FluidMotion.stats().active()!=0||!reference.onGround())return;
                require(FluidMotion.stats().released()==1&&FluidMotion.stats().acquired()==1,"one authority restored every original Tick exactly once");
                require(Math.abs(proxy.getY()-64)<.001&&Math.abs(reference.getY()-64)<.001,"after drain both return safely to the matching floor");
                var hp=view.enemies().entries().stream().filter(e->e.id()==101).findFirst().orElseThrow().health();require(hp==60&&reference.getHealth()==referenceHp,"short blocked swim and fall do not create damage: native="+hp+" reference="+reference.getHealth()+" initial="+referenceHp);
                var report=Map.of("result","PASS","backend","synthetic extra physical ceiling absent from scene metadata","max_planned_y",maxPlan,"max_actual_y",maxActual,"samples",samples,"stats",FluidMotion.stats());
                restore(server);stage=4;AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-fluid-partial.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                LoggerFactory.getLogger("sifucraft").info("[fluid-partial-test] PASS extra physical ceiling, partial burst no replay/penetration, retained heartbeat and safe restore: {}",report);return;
            }
            if(stage==4)finished=true;
        }catch(Exception|AssertionError error){LoggerFactory.getLogger("sifucraft").error("[fluid-partial-test] FAIL",error);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer ignored){FluidMotion.observe(null);if(reference!=null)reference.discard();if(level!=null)for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());originals.clear();}
}
