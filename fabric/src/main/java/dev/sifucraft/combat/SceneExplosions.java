package dev.sifucraft.combat;

import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.WorldMotionProtocol;
import dev.sifucraft.bridge.WorldMotionRequests;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Original-map geometry blocks explosions without becoming destructible MC blocks. */
public final class SceneExplosions {
    private SceneExplosions() {}
    public record Proof(UUID event,WorldMotionRequests.Scope scope,BlockPos center) {}
    private record Frame(ServerExplosion explosion,Proof proof,Map<EnemyProxy,CompletableFuture<WorldMotionProtocol.Reply>> damage) {}
    private static final ThreadLocal<Frame> current = new ThreadLocal<>();
    private static volatile java.util.function.Consumer<UUID> observer;
    private static volatile java.util.function.Consumer<ServerExplosion> before;
    public static void observeBefore(java.util.function.Consumer<ServerExplosion> value){
        if(value!=null&&!Boolean.getBoolean("sifucraft.boatExplosionSelfTest")&&!Boolean.getBoolean("sifucraft.cannonGroundSelfTest"))throw new IllegalStateException("isolated explosion fixture only");before=value;
    }
    public static void observe(java.util.function.Consumer<UUID> value){
        if(value!=null&&!Boolean.getBoolean("sifucraft.tntStressSelfTest")&&!Boolean.getBoolean("sifucraft.worldRecoverySelfTest")&&!Boolean.getBoolean("sifucraft.boatExplosionSelfTest")&&!Boolean.getBoolean("sifucraft.cannonGroundSelfTest"))throw new IllegalStateException("isolated TNT fixture only");observer=value;
    }
    /** Preserve TNT state while nearby native targets cannot accept a complete world event. */
    public static boolean canAdvance(net.minecraft.world.entity.item.PrimedTnt tnt){
        var view=WorldMotionWorld.current();if(view==null||view.enemies()==null)return true;
        var p=tnt.position();var placement=view.placement();
        boolean nearby=view.enemies().entries().stream().anyMatch(e->e.alive()&&p.distanceToSqr(new Vec3(
                placement.mcX(e.location().x()),placement.mcY(e.location().z()),placement.mcZ(e.location().y())))<256);
        return !nearby||view.nativeStatus()!=null&&(view.nativeStatus().capabilities()&WorldMotionProtocol.EXPLOSION_CAPABILITY)!=0&&view.requests().pending()<8;
    }
    /** Keep the complete vanilla projectile state while its impending blast cannot be represented. */
    public static boolean canAdvanceWind(net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge wind){
        var view=WorldMotionWorld.current();if(view==null||view.enemies()==null)return true;
        if(!dev.sifucraft.blocks.SceneFluidGeometry.knownVolume(wind.level(),wind.getBoundingBox().expandTowards(wind.getDeltaMovement()).inflate(4)))return false;
        var p=wind.position();var placement=view.placement();
        boolean nearby=view.enemies().entries().stream().anyMatch(e->e.alive()&&p.distanceToSqr(new Vec3(placement.mcX(e.location().x()),placement.mcY(e.location().z()),placement.mcZ(e.location().y())))<256);
        long now=dev.sifucraft.bridge.WorldWriter.clockMillis();
        long caps=WorldMotionProtocol.EXPLOSION_CAPABILITY|WorldMotionProtocol.IMPULSE_CAPABILITY;
        return !nearby||view.nativeStatus()!=null&&FluidMotion.currentGeometry(view)&&view.nativeStatus().flags()==0&&view.nativeStatus().atMs()<=now&&now-view.nativeStatus().atMs()<=200&&
            (view.nativeStatus().capabilities()&caps)==caps&&view.requests().pending()<8;
    }

    public static <T> T evaluate(ServerExplosion explosion,UUID identity,Supplier<T> action) {
        var view=WorldMotionWorld.current();
        var proof=view==null||view.nativeStatus()==null||(view.nativeStatus().capabilities()&WorldMotionProtocol.EXPLOSION_CAPABILITY)==0
                ?null:new Proof(identity,view.scope(),BlockPos.containing(explosion.center()));
        var previous=current.get();current.set(new Frame(explosion,proof,new HashMap<>()));
        if(Boolean.getBoolean("sifucraft.nativeWindSelfTest")&&explosion.getDirectSourceEntity() instanceof net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge)
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[wind-impulse-test] original blast center={} proof={} caps={}",explosion.center(),proof!=null,view==null||view.nativeStatus()==null?0:view.nativeStatus().capabilities());
        try{var prepare=before;if(prepare!=null)prepare.accept(explosion);T result=action.get();var listener=observer;if(listener!=null&&explosion.getDirectSourceEntity() instanceof net.minecraft.world.entity.item.PrimedTnt tnt)listener.accept(tnt.getUUID());return result;}
        finally{if(previous==null)current.remove();else current.set(previous);}
    }
    static Proof proof(EnemyProxy target,DamageSource source) {
        var frame=current.get();var view=WorldMotionWorld.current();
        return frame!=null&&frame.proof!=null&&frame.explosion.level()==target.level()&&frame.explosion.getDamageSource()==source&&
                view!=null&&frame.proof.scope.equals(view.scope())?frame.proof:null;
    }
    static void damageSubmitted(EnemyProxy target,DamageSource source,CompletableFuture<WorldMotionProtocol.Reply> future) {
        if(proof(target,source)!=null)current.get().damage.putIfAbsent(target,future);
    }
    /** Called only at the installed explosion's final pushFromExplosion invocation. */
    public static void impulse(EnemyProxy target,Vec3 impulse) {
        if(Boolean.getBoolean("sifucraft.nativeWindSelfTest"))org.slf4j.LoggerFactory.getLogger("sifucraft").info("[wind-impulse-test] original proxy push={} target={}",impulse,target.sifuKey());
        var frame=current.get();if(frame==null||frame.proof==null||!Double.isFinite(impulse.lengthSqr())||impulse.lengthSqr()==0)return;
        var view=WorldMotionWorld.current();if(view==null||view.enemies()==null||!view.scope().equals(frame.proof.scope))return;
        var entry=view.enemies().entries().stream().filter(e->e.key().equals(target.sifuKey())).findFirst().orElse(null);if(entry==null)return;
        var velocity=FluidMotion.pistonVelocity(target.sifuKey(),new Vec3(entry.velocity().x()/2000.0,entry.velocity().z()/2000.0,entry.velocity().y()/2000.0)).add(impulse);
        var proof=frame.proof;
        var direct=frame.explosion.getDirectSourceEntity();
        if(direct instanceof net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge wind&&
            (wind.getOwner() instanceof net.minecraft.world.entity.player.Player||MachineProjectiles.effectOrigin(wind,target)!=null)){
            WindImpulses.capture(target,proof,velocity);return;
        }
        var future=frame.damage.get(target);if(future==null)return;
        boolean passenger=BoatPassengers.holds(target.sifuKey());
        future.whenComplete((receipt,error)->target.level().getServer().execute(()->{
            if(error==null&&!target.isRemoved()&&!(target.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat))
                FluidMotion.explosionImpulse(target,proof.scope,proof.event,velocity,receipt,passenger);
        }));
    }

    public static boolean blocked(SifuCollision.Active scene, Vec3 from, Vec3 to) {
        return scene != null && (scene.solidAt(from.x, from.y, from.z) || scene.clip(from, to) != null);
    }

    public static BlockHitResult exposureHit(SifuCollision.Active scene, Vec3 from, Vec3 to,
                                             BlockHitResult vanilla) {
        if (scene == null) return vanilla;
        if (scene.solidAt(from.x, from.y, from.z)) {
            return new BlockHitResult(from, net.minecraft.core.Direction.UP,
                    net.minecraft.core.BlockPos.containing(from), true);
        }
        var nativeHit = scene.clip(from, to);
        return nativeHit != null && (vanilla.getType() == HitResult.Type.MISS ||
                from.distanceToSqr(nativeHit.getLocation()) < from.distanceToSqr(vanilla.getLocation()))
                ? nativeHit : vanilla;
    }

    /** Vanilla advances destructive rays by 0.3m; sweep the interval so a 1/8m wall is not skipped. */
    public static boolean stopsDestructiveRay(SifuCollision.Active scene, Vec3 center,
                                              double x, double y, double z,
                                              double dx, double dy, double dz) {
        if (scene == null) return false;
        var point = new Vec3(x, y, z);
        if (point.equals(center)) return scene.solidAt(x, y, z);
        double step = 0.30000001192092896; // installed 26.3 ServerExplosion ray increment
        return blocked(scene, point.add(-dx * step, -dy * step, -dz * step), point);
    }
}
