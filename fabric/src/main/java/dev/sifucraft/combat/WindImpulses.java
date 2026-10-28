package dev.sifucraft.combat;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.*;
import net.minecraft.world.phys.Vec3;
import static dev.sifucraft.bridge.WorldMotionProtocol.*;

/** A genuine wind-charge explosion may push without any HP loss. */
final class WindImpulses {
    private WindImpulses(){}
    static void capture(EnemyProxy target,SceneExplosions.Proof proof,Vec3 velocity){
        var view=WorldMotionWorld.current();long now=WorldWriter.clockMillis();
        if(view==null||!view.scope().equals(proof.scope())||!WorldEffects.effectsReady(target)||
            (view.nativeStatus().capabilities()&IMPULSE_CAPABILITY)==0||view.requests().pending()>=24||!Double.isFinite(velocity.lengthSqr())){
            if(Boolean.getBoolean("sifucraft.nativeWindSelfTest"))org.slf4j.LoggerFactory.getLogger("sifucraft").info("[wind-impulse-test] capture refused same_scope={} effects_ready={} pending={}",view!=null&&view.scope().equals(proof.scope()),WorldEffects.effectsReady(target),view==null?-1:view.requests().pending());
            return;
        }
        var entry=view.enemies().entries().stream().filter(e->e.key().equals(target.sifuKey())&&e.alive()).findFirst().orElse(null);if(entry==null)return;
        var origin=proof.event();var pos=proof.center();
        view.requests().request(view.scope(),id->new Command(IMPULSE,WORLD_EFFECT,id,0,now,origin.getMostSignificantBits(),origin.getLeastSignificantBits(),ShapeProtocol.mapHash("minecraft:wind_charge"),target.level().getGameTime(),
            view.scope().save(),view.scope().map(),view.nativeStatus().shapeRevision(),view.scope().world(),entry.id(),entry.spawn(),0,entry.location(),SifuFrame.Vec3.ZERO,entry.health(),entry.radius(),entry.halfHeight(),0,
            pos.getX(),pos.getY(),pos.getZ(),0,0,0,0,0,0,0)).whenComplete((reply,error)->target.level().getServer().execute(()->{
                if(error==null&&!target.isRemoved())FluidMotion.explosionImpulse(target,proof.scope(),origin,velocity,reply);
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[wind-impulse] target={} read_only_receipt={} HP={} error={}",target.sifuKey(),reply==null?-1:reply.status(),reply==null?-1:reply.health(),error==null?"":error.toString());
            }));
    }
}
