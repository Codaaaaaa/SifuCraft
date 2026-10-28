package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionProtocol;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.combat.SceneExplosions;
import dev.sifucraft.combat.VanillaFluidBody;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.storage.AtomicFiles;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One low-power original TNT: actual NPC HP and XYZ movement, with an original mob reference. */
final class NativeExplosionCheck implements FluidMotion.Observer {
    private static final class Reference extends Cow {
        private EntityDimensions size;
        Reference(ServerLevel level,float width,float height,long seed){super(EntityTypes.COW,level);size=EntityDimensions.scalable(width,height);refreshDimensions();getRandom().setSeed(seed);goalSelector.addGoal(0,new FloatGoal(this));setSilent(true);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.8f):size;}
    }
    private final boolean live=Boolean.getBoolean("sifucraft.nativeExplosionLive");
    private volatile boolean ready,finished;private volatile SifuFrame frame;
    private ServerLevel level;private EnemyFrame.Entry target;private Reference damaged,physics;private PrimedTnt tnt;
    private int stage,physicsTicks,completed;private long began,deadline,damageBefore;
    private float playerHealth,referenceHealth;private double initialY,peak,moved,maxPlanError,maxPlanVelocity;
    private Vec3 impulse;
    NativeExplosionCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-native-explosion-check-"),"disposable save required");FluidMotion.observe(this);}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("native explosion: "+why);}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished){mc.stop();return;}
        var v=WorldMotionWorld.current();ready=v!=null&&v.shapesCurrent()&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&
            (v.nativeStatus().capabilities()&WorldMotionProtocol.EXPLOSION_CAPABILITY)!=0&&v.enemies()!=null&&
            (live?!v.placement().map().equals("MotionFixture"):v.placement().map().equals("MotionFixture"));
    }
    @Override public void prepared(FluidMotion.Sample sample){
        if(target==null||!sample.key().equals(target.key()))return;
        require(physics==null,"one acquired explosion flight");initialY=peak=sample.position().y;
        physics=new Reference(level,sample.width(),sample.height(),sample.seed());physics.setId(sample.entityId());physics.setPos(sample.position());physics.setDeltaMovement(sample.velocity());physics.setOnGround(sample.grounded());
        require(impulse==null||sample.velocity().distanceTo(impulse)<1e-5,"full original explosion XYZ velocity retained");
    }
    @Override public void simulated(FluidMotion.Sample sample){
        if(target==null||!sample.key().equals(target.key()))return;
        level.tickNonPassenger(physics);physicsTicks++;
        maxPlanError=Math.max(maxPlanError,sample.position().distanceTo(physics.position()));maxPlanVelocity=Math.max(maxPlanVelocity,sample.velocity().distanceTo(physics.getDeltaMovement()));
        require(maxPlanError<.04&&maxPlanVelocity<.09,"original XYZ integrator stays within actual-capsule contact tolerance");
    }
    @Override public void completed(FluidMotion.Sample sample){if(target!=null&&sample.key().equals(target.key())){completed++;peak=Math.max(peak,sample.position().y);}}
    void tick(MinecraftServer server){
        if(finished)return;if(began==0)began=System.currentTimeMillis();
        try{
            require(System.currentTimeMillis()-began<120000,"bounded setup/action deadline");var view=WorldMotionWorld.current();
            if(stage==0){
                if(!ready||frame==null||frame.health()<=0)return;
                level=server.overworld();target=view.enemies().entries().stream().filter(e->e.alive()&&e.health()>=40&&EnemyMirrors.hitAttacker(e.id(),e.spawn())!=null).findFirst().orElse(null);if(target==null)return;
                var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());var feet=proxy.position();Vec3 center=null;
                for(var offset:List.of(new Vec3(1.25,.35,0),new Vec3(-1.25,.35,0),new Vec3(0,.35,1.25),new Vec3(0,.35,-1.25))){
                    var at=feet.add(offset);
                    if(!SceneExplosions.blocked(view.scene(),at,proxy.getEyePosition())&&level.clip(new ClipContext(at,proxy.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,proxy)).getType()==HitResult.Type.MISS){center=at;break;}
                }
                require(center!=null,"clear short blast exposure in the imported scene");
                damaged=new Reference(level,proxy.getBbWidth(),proxy.getBbHeight(),91);damaged.setNoAi(true);damaged.setNoGravity(true);damaged.setPos(feet);
                damaged.getAttribute(Attributes.MAX_HEALTH).setBaseValue(proxy.getMaxHealth());damaged.setHealth(proxy.getHealth());referenceHealth=damaged.getHealth();
                require(level.addFreshEntity(damaged),"registered original damage/impulse reference");
                tnt=new PrimedTnt(EntityTypes.TNT,level);tnt.setPos(center);tnt.setNoGravity(true);tnt.setDeltaMovement(Vec3.ZERO);tnt.setFuse(20);
                var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());tnt.saveWithoutId(out);var tag=out.buildResult();tag.putFloat("explosion_power",1);
                tnt.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),tag));require(level.addFreshEntity(tnt),"one original low-power TNT; no native blast");
                playerHealth=frame.health();damageBefore=WorldEffects.stats().applied();deadline=System.currentTimeMillis()+15000;stage=1;
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[native-explosion-test] armed original TNT power=1 fuse=20 UUID={} target={}/{} hp={} center={} live={}",tnt.getUUID(),target.id(),target.spawn(),target.health(),center,live);return;
            }
            if(!ready)return;
            require(System.currentTimeMillis()<deadline,"bounded stage="+stage+" effects="+WorldEffects.stats()+" motion="+FluidMotion.stats());
            require(frame.health()==playerHealth,"player outside blast retains native HP");
            var current=view.enemies().entries().stream().filter(e->e.key().equals(target.key())).findFirst().orElse(null);require(current!=null&&current.alive(),"target remains alive in the same activation");
            moved=Math.max(moved,Math.sqrt(Math.pow(current.location().x()-target.location().x(),2)+Math.pow(current.location().y()-target.location().y(),2))/100.0);
            if(stage==1){
                if(!tnt.isRemoved())return;
                impulse=damaged.getDeltaMovement();referenceHealth-=damaged.getHealth();damaged.discard();stage=2;
                require(referenceHealth>0&&referenceHealth*3<target.health(),"positive nonlethal original damage control");return;
            }
            if(stage==2){
                if(WorldEffects.stats().applied()<=damageBefore||FluidMotion.stats().active()!=0||view.requests().pending()!=0||completed==0)return;
                require(Math.abs(target.health()-current.health()-referenceHealth*3)<.02,"one fully resolved original damage application at NPC 3:1");
                require(physicsTicks>1&&moved>.05&&peak>initialY+.005,"real XYZ force, including vertical rise, completed under actual motion receipts");
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p10-native-explosion.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "passed",true,"live",live,"power",1,"mc_damage",referenceHealth,"native_health",current.health(),"native_horizontal_m",moved,
                    "native_rise_m",peak-initialY,"physics_ticks",physicsTicks,"plan_error_m",maxPlanError,"motion",FluidMotion.stats()),out));
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[native-explosion-test] PASS mc_damage={} native_hp={} moved={} rise={} ticks={} completed={} motion={}",referenceHealth,current.health(),moved,peak-initialY,physicsTicks,completed,FluidMotion.stats());
                restore(server);finished=true;
            }
        }catch(Exception|AssertionError error){restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[native-explosion-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer server){FluidMotion.observe(null);if(damaged!=null&&!damaged.isRemoved())damaged.discard();if(physics!=null)physics.discard();if(tnt!=null&&!tnt.isRemoved())tnt.discard();}
}
