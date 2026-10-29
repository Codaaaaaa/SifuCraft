package dev.sifucraft.combat;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** The nearer original scene face supplements each projectile's vanilla ray. */
public final class SceneProjectiles {
    private SceneProjectiles(){}
    public static HitResult clip(Projectile projectile,HitResult vanilla){
        var scene=SifuCollision.active();if(scene==null||projectile.level().dimension()!=Level.OVERWORLD)return vanilla;
        var start=projectile.position();var end=vanilla.getType()==HitResult.Type.MISS?start.add(projectile.getDeltaMovement()):vanilla.getLocation();
        var hit=scene.clip(start,end);
        return hit==null||vanilla.getType()!=HitResult.Type.MISS&&start.distanceToSqr(hit.getLocation())>=start.distanceToSqr(vanilla.getLocation())-1e-8?vanilla:hit;
    }
}
