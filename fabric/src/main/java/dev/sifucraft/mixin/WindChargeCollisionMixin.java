package dev.sifucraft.mixin;

import dev.sifucraft.collision.SifuCollision;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Projectile rays need the original scene in addition to the extra movement collision shapes. */
@Mixin(ProjectileUtil.class)
public abstract class WindChargeCollisionMixin {
    @Unique private static final AtomicLong sifucraft$contacts = new AtomicLong();

    @Inject(method = "getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;",
            at = @At("RETURN"), cancellable = true)
    private static void sifucraft$windOnScene(Entity projectile, Predicate<Entity> filter, ClipContext.Block mode,
                                             CallbackInfoReturnable<HitResult> cir) {
        if (!(projectile instanceof Projectile) || projectile.level().dimension() != Level.OVERWORLD) return;
        var active = SifuCollision.active();
        if (active == null) return;
        var start = projectile.position();
        var vanilla = cir.getReturnValue();
        var end = vanilla.getType() == HitResult.Type.MISS ? start.add(projectile.getDeltaMovement()) : vanilla.getLocation();
        var scene = active.clip(start, end);
        if (scene == null || (vanilla.getType() != HitResult.Type.MISS &&
                start.distanceToSqr(scene.getLocation()) >= start.distanceToSqr(vanilla.getLocation()) - 1e-8)) return;
        cir.setReturnValue(scene);
        if (projectile.level() instanceof ServerLevel && sifucraft$contacts.getAndIncrement() < 60)
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[{}] hit imported scene at {} face={}", projectile instanceof WindCharge?"wind-charge":"projectile",scene.getLocation(), scene.getDirection());
    }
}
