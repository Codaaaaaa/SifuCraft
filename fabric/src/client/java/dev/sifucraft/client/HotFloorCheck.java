package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

/** Original stepOn/hurt callbacks and actual separate-process HP, no synthetic hurt calls. */
final class HotFloorCheck {
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setSilent(true);refreshDimensions();getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);setHealth(20);}
        @Override protected void registerGoals(){} // keep original gravity/travel, with no wandering authority
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<Integer,Reference> references=new LinkedHashMap<>();
    private final boolean missing=Boolean.getBoolean("sifucraft.hotFloorExpectMissing");
    private final boolean hover=Boolean.getBoolean("sifucraft.hotFloorHoverTest");
    private final boolean stale=Boolean.getBoolean("sifucraft.hotFloorExpectStale");
    private final int cycles=Integer.getInteger("sifucraft.hotFloorCycles",1);
    private final ArrayList<Long> referenceTicks=new ArrayList<>(),nativeTicks=new ArrayList<>();
    private float previousHealth=20;private long previousSent,firstNativeAt;
    private volatile boolean finished;private int stage,comparisons,stable;private long started;private ServerLevel level;
    HotFloorCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-hot-floor-check-")&&cycles>=1&&cycles<=6,"disposable save and bounded cycle count required");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("hot floor: "+why);}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private float hp(int id){return WorldMotionWorld.current().enemies().entries().stream().filter(e->e.id()==id&&e.spawn()==1).findFirst().orElseThrow().health();}
    private void set(BlockPos pos,BlockState state){originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var v=WorldMotionWorld.current();if(v==null||v.nativeStatus()==null||!v.placement().map().equals("MotionFixture"))return;
            if(stage==0){
                if(!v.shapesCurrent()||EnemyMirrors.hitAttacker(101,1)==null||EnemyMirrors.hitAttacker(102,1)==null)return;
                level=server.overworld();started=System.currentTimeMillis();
                for(int x=3;x<=6;x++)for(int z=-1;z<=4;z++)set(new BlockPos(x,64,z),Blocks.MAGMA_BLOCK.defaultBlockState());
                for(int id:new int[]{101,102}){
                    var p=EnemyMirrors.hitAttacker(id,1);require(Math.abs(p.getY()-(hover?65.03:65))<1e-5,"synthetic initial physical feet match explicit fixture height, no Native teleport");
                    var r=new Reference(level);r.setPos(p.position());r.setOnGround(!hover);references.put(id,r);
                    if(id==102){p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,2000));r.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,2000));}
                }
                stage=1;return;
            }
            require(System.currentTimeMillis()-started<15000,"bounded deadline stage="+stage+" "+FluidMotion.stats()+" "+WorldEffects.stats());
            for(int id:new int[]{101,102}){
                var p=EnemyMirrors.hitAttacker(id,1);require(p!=null,"unchanged activation");var r=references.get(id);r.setPos(p.position());r.tick();comparisons++;
            }
            float referenceHealth=references.get(101).getHealth();
            if(referenceHealth<previousHealth){referenceTicks.add(level.getGameTime());previousHealth=referenceHealth;}
            long sent=WorldEffects.stats().sent();if(sent>previousSent){require(sent==previousSent+1,"at most one hot-floor effect per server tick");nativeTicks.add(level.getGameTime());previousSent=sent;if(firstNativeAt==0)firstNativeAt=System.currentTimeMillis();}
            require(references.get(102).getHealth()==20&&hp(102)==60,"original fire resistance prevents hot-floor damage");
            if(stage==1){
                if(references.get(101).getHealth()==20)return;
                if(missing){require(referenceHealth==19&&WorldEffects.stats().sent()==0&&hp(101)==60&&FluidMotion.stats().acquired()==0,"unchanged baseline misses a real original hot-floor callback");complete(server,"EXPECTED_MISSING_BASELINE");return;}
                if(stale){if(firstNativeAt==0||System.currentTimeMillis()-firstNativeAt<2000)return;require(hp(101)==57&&WorldEffects.stats().sent()==1&&referenceTicks.size()>=3&&FluidMotion.stats().renewed()>5,"old renewal timestamp stops physical floor callbacks despite active fresh heartbeats");complete(server,"EXPECTED_STALE_RENEWAL_BASELINE");return;}
                if(hp(101)>60-cycles*3)return;
                require(hp(101)==60-cycles*3&&referenceHealth==20-cycles&&WorldEffects.stats().applied()==cycles,"actual Native HP matches original repeated callbacks without duplication");
                for(int i=1;i<cycles;i++)require(Math.abs((nativeTicks.get(i)-nativeTicks.get(i-1))-(referenceTicks.get(i)-referenceTicks.get(i-1)))<=1,"Native floor source keeps the original hurt period within one server tick");
                for(int id:new int[]{101,102})require(!WorldEffects.canCapture(EnemyMirrors.hitAttacker(id,1),level.damageSources().hotFloor()),"actual callback proof was restored and cannot be reused outside its scope");
                if(hover)require(FluidMotion.stats().steps()>0&&Math.abs(EnemyMirrors.hitAttacker(101,1).getY()-65)<.0025,"hover candidate only damages after actual physical downward receipt reached the top face");
                for(var pos:originals.keySet())level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                require(hp(101)==60-cycles*3&&references.get(101).getHealth()==20-cycles&&WorldEffects.stats().sent()==cycles,"leaving the hot surface stops further damage");
                if(++stable<25||FluidMotion.stats().active()!=0||v.requests().pending()!=0||!v.shapesCurrent())return;
                require(FluidMotion.stats().acquired()>=2&&FluidMotion.stats().released()>=2&&FluidMotion.stats().failed()==0,"both actual physical bindings restored original ticks");
                complete(server,"PASS");
            }
        }catch(Exception|AssertionError e){restore(server);finished=true;LoggerFactory.getLogger("sifucraft").error("[hot-floor-test] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    private void complete(MinecraftServer server,String result)throws Exception{
        AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-hot-floor.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result",result,"backend","separate-process AABB endpoint, not real Sifu","original_mc_damage",20-references.get(101).getHealth(),"native_lost",60-hp(101),"protected_native_hp",hp(102),"reference_samples",comparisons,"stable_ticks",stable,"cycles",cycles,"reference_ticks",referenceTicks,"native_source_ticks",nativeTicks),out));
        LoggerFactory.getLogger("sifucraft").info("[hot-floor-test] {} referenceSamples={} {} {}",result,comparisons,FluidMotion.stats(),WorldEffects.stats());restore(server);finished=true;
    }
    void restore(MinecraftServer ignored){for(var r:references.values())r.discard();references.clear();if(level!=null){for(int id:new int[]{101,102}){var p=EnemyMirrors.hitAttacker(id,1);if(p!=null)p.removeEffect(MobEffects.FIRE_RESISTANCE);}for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());}originals.clear();}
}
