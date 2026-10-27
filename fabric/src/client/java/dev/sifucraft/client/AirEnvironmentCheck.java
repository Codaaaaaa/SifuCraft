package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual receipted endpoints under a physical ceiling, compared to original Cow breathing. */
final class AirEnvironmentCheck {
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);setSilent(true);getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);setHealth(20);refreshDimensions();}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
        void follow(EnemyProxy proxy){var at=proxy.position();setPos(at);setDeltaMovement(Vec3.ZERO);((ServerLevel)level()).tickNonPassenger(this);setPos(at);setDeltaMovement(Vec3.ZERO);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<Integer,Reference> references=new LinkedHashMap<>();
    private ServerLevel level;private volatile boolean finished;private int stage,compared,dryTicks,bubbleTicks,protectedWetTicks;
    private long started,stageAt;private int minAir=300;private float damage;
    AirEnvironmentCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-air-check-"),"disposable save required");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("air environment: "+why);}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private void set(BlockPos pos,BlockState state){originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void water(int from,int to,BlockState state){for(int x=3;x<=6;x++)for(int z=-1;z<=4;z++)for(int y=from;y<=to;y++)set(new BlockPos(x,y,z),state);}
    private EnemyProxy proxy(int id){var p=EnemyMirrors.hitAttacker(id,1);require(p!=null,"same live activation "+id);return p;}
    private float hp(int id){var v=WorldMotionWorld.current();return v.enemies().entries().stream().filter(e->e.id()==id&&e.spawn()==1).findFirst().orElseThrow().health();}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null||!view.placement().map().equals("MotionFixture"))return;
            // Real entities keep breathing while a changed collision section is
            // being acknowledged. Tick the independent reference at the same
            // 20Hz cadence rather than skipping it behind that barrier.
            if((stage==0||stage==10)&&!view.shapesCurrent())return;
            if(stage==0){
                level=server.overworld();started=stageAt=System.currentTimeMillis();
                for(int x=2;x<=7;x++)for(int z=-2;z<=5;z++)for(int y=63;y<=70;y++)set(new BlockPos(x,y,z),(y==63||x==2||x==7||z==-2||z==5?Blocks.STONE:Blocks.AIR).defaultBlockState());
                stage=10;return;
            }
            require(System.currentTimeMillis()-started<75000,"bounded deadline stage="+stage+" motion="+FluidMotion.stats()+" effects="+WorldEffects.stats());
            if(stage==10){
                if(System.currentTimeMillis()-stageAt<1500)return;
                for(int id:new int[]{101,102}){
                    var p=proxy(id);var r=new Reference(level);r.follow(p);r.setAirSupply(p.getAirSupply());references.put(id,r);
                    if(id==102){p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,2000));r.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,2000));}
                }
                require(proxy(101).getAirSupply()==300,"full initial air was retained, no accelerated drowning");
                water(64,68,Blocks.WATER.defaultBlockState());
                stage=1;LoggerFactory.getLogger("sifucraft").info("[air-test] initialized full breathing and protected reference lanes");return;
            }
            if(stage>=1&&stage<=3){
                for(int id:new int[]{101,102}){
                    var p=proxy(id);var r=references.get(id);r.follow(p);
                    require(p.getAirSupply()==r.getAirSupply(),"original air matches target="+id+" stage="+stage+" proxy="+p.getAirSupply()+" reference="+r.getAirSupply());compared++;
                }
                minAir=Math.min(minAir,proxy(101).getAirSupply());
            }
            if(stage==1){
                if(proxy(102).isEyeInFluid(FluidTags.WATER))protectedWetTicks++;
                require(hp(102)==60&&references.get(102).getHealth()==20,"water breathing prevents Native and reference damage");
                if(references.get(101).getHealth()==20)return;
                damage=20-references.get(101).getHealth();require(damage==2&&minAir<=0&&protectedWetTicks>=300,"original full-air drowning callback and independently submerged protected lane");
                if(WorldEffects.stats().applied()==0)return;
                if(hp(101)==60)return; // enemy snapshots and the reliable receipt have independent publication clocks
                require(hp(101)==60-damage*3&&WorldEffects.stats().applied()==1,"one original resolved drown effect became one actual Native HP loss");
                water(65,68,Blocks.AIR.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                require(hp(101)==54&&hp(102)==60&&references.get(101).getHealth()==18,"exposed head takes no further damage");
                if(proxy(101).isEyeInFluid(FluidTags.WATER)||proxy(102).isEyeInFluid(FluidTags.WATER)){dryTicks=0;return;}
                if(++dryTicks<80||proxy(101).getAirSupply()!=proxy(101).getMaxAirSupply())return;
                for(int id:new int[]{101,102}){proxy(id).removeEffect(MobEffects.WATER_BREATHING);references.get(id).removeEffect(MobEffects.WATER_BREATHING);proxy(id).setAirSupply(80);references.get(id).setAirSupply(80);}
                for(int x=3;x<=6;x++)for(int z=-1;z<=4;z++)set(new BlockPos(x,63,z),Blocks.SOUL_SAND.defaultBlockState());
                water(64,68,Blocks.WATER.defaultBlockState());stage=3;return;
            }
            if(stage==3){
                for(int id:new int[]{101,102}){var p=proxy(id);require(hp(id)==(id==101?54:60),"bubble recovery does not invent damage");if(!level.getBlockState(BlockPos.containing(p.getX(),p.getEyeY(),p.getZ())).is(Blocks.BUBBLE_COLUMN))return;}
                if(++bubbleTicks<80||proxy(101).getAirSupply()!=300||proxy(102).getAirSupply()!=300)return;
                water(64,68,Blocks.AIR.defaultBlockState());stage=4;return;
            }
            if(stage==4){
                if(FluidMotion.stats().active()!=0||view.requests().pending()!=0)return;
                require(WorldEffects.stats().sent()==1&&WorldEffects.stats().applied()==1&&WorldEffects.stats().rejected()==0&&WorldEffects.stats().lost()==0,"exactly one environment effect, no replay or rejected source");
                require(FluidMotion.stats().steps()>0&&FluidMotion.stats().blocked()>0&&FluidMotion.stats().released()>0&&FluidMotion.stats().failed()==0,"real physical ceiling and original handback used production movement");
                restore(server);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-air.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","separate-process physical ceiling; not real Sifu","air_comparisons",compared,"minimum_air",minAir,"mc_drown_damage",damage,"native_loss",damage*3,"protected_native_hp",hp(102),"protected_underwater_ticks",protectedWetTicks,"exposed_head_ticks",dryTicks,"bubble_ticks",bubbleTicks),out));
                finished=true;LoggerFactory.getLogger("sifucraft").info("[air-test] PASS {} original air comparisons, full-air drown/Native damage, breathing protection, exposed head and bubble recovery; {} {}",compared,FluidMotion.stats(),WorldEffects.stats());
            }
        }catch(Exception|AssertionError e){restore(server);finished=true;LoggerFactory.getLogger("sifucraft").error("[air-test] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){for(var r:references.values())r.discard();references.clear();if(level!=null){for(int id:new int[]{101,102}){var p=EnemyMirrors.hitAttacker(id,1);if(p!=null)p.removeEffect(MobEffects.WATER_BREATHING);}for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());}originals.clear();}
}
