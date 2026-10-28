package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.combat.*;
import dev.sifucraft.combat.WorldEffects;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/** Two independent original TNT blasts: a vanilla boat/rider lane and one actual Native rider. */
final class BoatExplosionFixture implements FluidMotion.Observer {
    private static final class Reference extends Cow {
        private EntityDimensions size;
        Reference(ServerLevel level,float width,float height,long seed){super(EntityTypes.COW,level);size=EntityDimensions.scalable(width,height);refreshDimensions();getRandom().setSeed(seed);goalSelector.addGoal(0,new FloatGoal(this));setSilent(true);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.8f):size;}
    }
    private final ServerLevel level;
    private final AbstractBoat boat,referenceBoat;
    private final EnemyFrame.Entry initial;
    private final Reference rider,damageReference;
    private Reference physics;
    private PrimedTnt tnt;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<ItemEntity> drops=new ArrayList<>();
    private int stage,ticks,physicsTicks,completed;
    private float referenceHealth;
    private double planError,velocityError,moved,initialY,peak;
    private long effectsBefore;
    private long actualBlastAt;
    private long lastDiagnostic;
    private Vec3 impulse;
    private Vec3 actualImpulse;
    private boolean closed;
    private final String scenario=System.getProperty("sifucraft.boatExplosionScenario","destroy");
    private final boolean damaged=scenario.equals("damaged");
    private final boolean survives=scenario.equals("survive");
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("boat explosion: "+why);}
    BoatExplosionFixture(ServerLevel level,AbstractBoat boat,EnemyFrame.Entry initial){
        this.level=level;this.boat=boat;this.initial=initial;
        require(Set.of("destroy","damaged","survive").contains(scenario),"known original boat explosion scenario");
        var proxy=EnemyMirrors.hitAttacker(initial.id(),initial.spawn());
        require(proxy!=null&&proxy.getVehicle()==boat&&BoatPassengers.seated(initial.key()),"actual leased passenger before blast");
        referenceBoat=(AbstractBoat)EntityTypes.OAK_BOAT.create(level,EntitySpawnReason.COMMAND);
        require(referenceBoat!=null,"original reference boat factory");
        Vec3 offset=null;
        for(double z:new double[]{2.7,-2.7}){
            var candidate=new Vec3(0,0,z);boolean clear=true;
            for(var shape:level.getBlockCollisions(boat,boat.getBoundingBox().move(candidate).inflate(.05)))if(!shape.isEmpty())clear=false;
            if(clear){offset=candidate;break;}
        }
        require(offset!=null,"separate reference lane fits actual scene");
        referenceBoat.setPos(boat.position().add(offset));referenceBoat.setYRot(boat.getYRot());referenceBoat.setDeltaMovement(boat.getDeltaMovement());
        require(level.addFreshEntity(referenceBoat),"registered original reference boat");
        rider=new Reference(level,proxy.getBbWidth(),proxy.getBbHeight(),91);rider.setNoAi(true);
        rider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(proxy.getMaxHealth());rider.setHealth(proxy.getHealth());
        rider.setPos(proxy.position().add(offset));require(level.addFreshEntity(rider)&&rider.startRiding(referenceBoat),"same size original reference passenger");
        damageReference=new Reference(level,proxy.getBbWidth(),proxy.getBbHeight(),91);damageReference.setNoAi(true);damageReference.setNoGravity(true);
        damageReference.getAttribute(Attributes.MAX_HEALTH).setBaseValue(proxy.getMaxHealth());damageReference.setHealth(proxy.getHealth());
        damageReference.setPos(proxy.position().add(0,4,0));require(level.addFreshEntity(damageReference),"registered co-located original damage control");
        SceneExplosions.observeBefore(blast->{
            if(blast.getDirectSourceEntity()!=tnt)return;
            var targetBoat=stage==1?referenceBoat:boat;
            if(damaged)require(targetBoat.hurtServer(level,level.damageSources().generic(),2)&&!targetBoat.isRemoved(),"original pre-damaged boat survives until its TNT blast");
            if(stage!=2)return;
            actualBlastAt=System.currentTimeMillis();
            var actual=EnemyMirrors.hitAttacker(initial.id(),initial.spawn());require(actual!=null,"actual mirror immediately before original blast");
            damageReference.setPos(actual.position());damageReference.setDeltaMovement(Vec3.ZERO);
        });
        SceneExplosions.observe(id->{if(stage==2&&tnt!=null&&id.equals(tnt.getUUID()))actualImpulse=damageReference.getDeltaMovement();});
        referenceHealth=rider.getHealth();effectsBefore=WorldEffects.stats().applied();
        report.put("native_target",initial.key());report.put("reference_lane_m",2.7);report.put("power",1);
        report.put("scenario",scenario);report.put("boat_damage_before_blast",damaged?2:0);
    }
    private PrimedTnt arm(AbstractBoat target){
        var result=new PrimedTnt(EntityTypes.TNT,level);result.setPos(target.position().add(scenario.equals("destroy")?.9:1.25,scenario.equals("destroy")?.3:.5,0));result.setNoGravity(true);result.setDeltaMovement(Vec3.ZERO);result.setFuse(10);
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());result.saveWithoutId(out);var tag=out.buildResult();tag.putFloat("explosion_power",1);
        result.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),tag));require(level.addFreshEntity(result),"one original TNT with original fuse");return result;
    }
    boolean tick(WorldMotionWorld.View view){
        long now=System.currentTimeMillis();
        if(stage>=1&&now-lastDiagnostic>=2000){lastDiagnostic=now;
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-explosion-test] stage={} boat={} removed={} tnt={} fuse={} removed={} advance={} seat_error={} pending={} hp={}",stage,boat.position(),boat.isRemoved(),tnt.position(),tnt.getFuse(),tnt.isRemoved(),SceneExplosions.canAdvance(tnt),BoatPassengers.seatError(initial.key()),view.requests().pending(),damageReference.getHealth());
        }
        var current=view.enemies().entries().stream().filter(e->e.key().equals(initial.key())).findFirst().orElse(null);
        require(current!=null&&current.alive(),"same living Native activation");
        if(stage==0){
            if(++ticks<10||!BoatPassengers.seated(initial.key()))return false;
            var proxy=EnemyMirrors.hitAttacker(initial.id(),initial.spawn());
            require(Math.abs(referenceBoat.getY()-boat.getY())<.03&&Math.abs(rider.getY()-proxy.getY())<.03,"matching original boat/rider flotation before reference blast");
            tnt=arm(referenceBoat);stage=1;return false;
        }
        if(stage==1){
            if(!tnt.isRemoved())return false;
            require(survives?!referenceBoat.isRemoved()&&rider.getVehicle()==referenceBoat:referenceBoat.isRemoved()&&!rider.isPassenger(),"original blast boat survival and passenger relation match the scenario");
            referenceHealth-=rider.getHealth();impulse=rider.getDeltaMovement();rider.discard();
            require(referenceHealth>0&&referenceHealth*3<initial.health(),"nonlethal original passenger damage reference");
            require(current.health()==initial.health(),"separate reference blast cannot reach the actual Native passenger");
            report.put("reference_boat_lane_damage_mc",referenceHealth);report.put("reference_boat_lane_impulse",impulse.toString());
            tnt=arm(boat);stage=2;
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-explosion-test] reference scenario={} damage={} impulse={}; armed actual boat",scenario,referenceHealth,impulse);return false;
        }
        if(!tnt.isRemoved())return false;
        require(boat.isRemoved()!=survives,"actual boat survival matches the original reference");
        referenceHealth=initial.health()/3-damageReference.getHealth();
        moved=Math.max(moved,Math.hypot(current.location().x()-initial.location().x(),current.location().y()-initial.location().y())/100);
        if(FluidMotion.stats().active()!=0||view.requests().pending()!=0)return false;
        if(survives){if(!BoatPassengers.seated(initial.key())||BoatPassengers.stats().boarded()<2||BoatPassengers.seatError(initial.key())>.03)return false;}
        else if(BoatPassengers.stats().active()!=0||completed==0)return false;
        var proxy=EnemyMirrors.hitAttacker(initial.id(),initial.spawn());require(proxy!=null&&(survives?proxy.getVehicle()==boat:!proxy.isPassenger()),"actual seat relation matches the surviving or destroyed original boat");
        require(Math.abs(initial.health()-current.health()-referenceHealth*3)<.02&&WorldEffects.stats().applied()==effectsBefore+1,"original passenger damage maps once at 3:1");
        require(BoatPassengers.stats().failed()==0&&moved>.01&&(survives?physics==null&&FluidMotion.stats().acquired()==0:physicsTicks>1),"one physical authority and successful actual passenger recovery");
        drops.addAll(level.getEntitiesOfClass(ItemEntity.class,boat.getBoundingBox().inflate(7),item->item.getItem().is(Items.OAK_BOAT)));
        int count=drops.stream().mapToInt(item->item.getItem().getCount()).sum();require(count==(survives?0:2),"original boat item conservation, got="+count);
        report.put("boat_drops",count);report.put("native_health",current.health());report.put("native_horizontal_m",moved);report.put("native_rise_m",peak-initialY);
        report.put("reference_damage_mc",referenceHealth);report.put("reference_impulse",actualImpulse.toString());
        if(survives)report.put("final_native_seat_error_m",BoatPassengers.seatError(initial.key()));
        report.put("physics_ticks",physicsTicks);report.put("plan_error_m",planError);report.put("velocity_error",velocityError);report.put("motion",FluidMotion.stats());
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-explosion-test] PASS original boat destruction/drops/passenger damage/XYZ motion and handback {}",report);return true;
    }
    @Override public void prepared(FluidMotion.Sample sample){
        if(closed||!sample.key().equals(initial.key())||stage<2)return;
        require(!survives,"a still-seated vanilla passenger cannot acquire an independent explosion flight");
        require(physics==null,"one acquired post-destruction flight");initialY=peak=sample.position().y;
        require(actualImpulse!=null&&sample.velocity().distanceTo(actualImpulse)<1e-5,"post-destruction flight receives the original explosion impulse exactly once");
        report.put("native_acquire_latency_ms",System.currentTimeMillis()-actualBlastAt);
        physics=new Reference(level,sample.width(),sample.height(),sample.seed());
        physics.setId(sample.entityId());physics.setPos(sample.position());physics.setDeltaMovement(sample.velocity());physics.setOnGround(sample.grounded());
    }
    @Override public void simulated(FluidMotion.Sample sample){
        if(!sample.key().equals(initial.key())||physics==null)return;
        level.tickNonPassenger(physics);physicsTicks++;planError=Math.max(planError,sample.position().distanceTo(physics.position()));velocityError=Math.max(velocityError,sample.velocity().distanceTo(physics.getDeltaMovement()));
        require(planError<.04&&velocityError<.09,"original post-destruction XYZ integration position_error="+planError+" velocity_error="+velocityError+" tick="+physicsTicks+" actual="+sample.position()+" reference="+physics.position());
    }
    @Override public void beforeSimulation(FluidMotion.Sample sample,VanillaFluidBody body){
        if(!sample.key().equals(initial.key())||physics==null)return;
        // The real capsule can correct a voxel prediction at a wall/floor.
        // Compare the next original tick from that same actual starting pose.
        physics.setPos(sample.position());physics.setDeltaMovement(sample.velocity());physics.setOnGround(sample.grounded());
    }
    @Override public void completed(FluidMotion.Sample sample){if(sample.key().equals(initial.key())&&physics!=null){completed++;peak=Math.max(peak,sample.position().y);}}
    boolean canDrain(){return completed>=12&&peak>initialY+.005;}
    void settled(WorldMotionWorld.View view){
        var current=view.enemies().entries().stream().filter(e->e.key().equals(initial.key())).findFirst().orElse(null);
        require(current!=null&&Math.abs(current.health()-((Number)report.get("native_health")).floatValue())<.02,"final cleanup does not add Native landing damage");
        require(FluidMotion.stats().failed()==0,"final causal passenger fall has no failed motion transaction");
        report.put("motion_after_cleanup",FluidMotion.stats());
    }
    Map<String,Object> report(){return Map.copyOf(report);}
    void close(){closed=true;SceneExplosions.observeBefore(null);SceneExplosions.observe(null);rider.discard();damageReference.discard();referenceBoat.discard();if(tnt!=null)tnt.discard();if(physics!=null)physics.discard();for(var item:drops)item.discard();}
}
