package dev.sifucraft.mixin;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the player slide along Sifu's walls at an angle the way it slides along a smooth wall in Sifu.
 * Such a wall is a staircase of 1/8-block cells, and Minecraft resolves a move one axis at a time:
 * against the steps the player slows down, and walking along an axis into a 45-degree wall stops
 * it dead. When a move of the local player is cut short by Sifu geometry, it is retried with the
 * part going into the walls (their mean normal, from the cells around the player at body height)
 * taken out; and the velocity keeps its part along the wall instead of losing both axes. Only the
 * client moves the player; the server checks the result as any other move. Not while sneaking, so
 * that sneaking still stops at edges. Also lets the player aim at Sifu's surfaces.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Unique private static final double MIN_MOVE = 1.0E-4;
    @Unique private static final double ABOVE_STEP = 0.05;
    @Unique private static final double REACH = 0.2;

    @Unique private boolean sifucraft$retrying;
    @Unique private Vec3 sifucraft$tangent;
    @Unique private Vec3 sifucraft$velocity;

    @Shadow protected abstract float getBlockSpeedFactor();

    @Shadow
    private Vec3 collide(Vec3 movement) {
        throw new AssertionError();
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void sifucraft$beforeMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        sifucraft$tangent = null;
        sifucraft$velocity = ((Entity) (Object) this).getDeltaMovement();
    }

    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void sifucraft$slideAlongSifuWalls(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (sifucraft$retrying || !(self instanceof Player) || !self.level().isClientSide()
                || !self.isLocalInstanceAuthoritative() || self.isSteppingCarefully()) {
            return;
        }
        SifuCollision.Active active = SifuCollision.active();
        Vec3 vanilla = cir.getReturnValue();
        double wanted = movement.horizontalDistance(), got = vanilla.horizontalDistance();
        if (active == null || wanted < MIN_MOVE || got > wanted - MIN_MOVE) return;
        AABB box = self.getBoundingBox();
        Vec3 normal = active.wallNormal(box, box.minY + self.maxUpStep() + ABOVE_STEP, REACH);
        if (normal == null) return;
        double into = movement.x * normal.x + movement.z * normal.z;
        if (into <= 0) return;
        Vec3 along = new Vec3(movement.x - into * normal.x, movement.y, movement.z - into * normal.z);
        if (along.horizontalDistance() < MIN_MOVE) return; // head-on: nothing to slide along
        sifucraft$retrying = true;
        Vec3 slid;
        try {
            slid = collide(along);
        } finally {
            sifucraft$retrying = false;
        }
        if (slid.horizontalDistance() <= got + MIN_MOVE) return;
        sifucraft$tangent = new Vec3(along.x, 0, along.z).normalize();
        cir.setReturnValue(slid);
    }

    /**
     * What the player looks at includes Sifu's surfaces, nearer than any block or entity behind them:
     * a block can be placed against a Sifu floor or wall (the hit names the block in front of it).
     */
    @Inject(method = "pick", at = @At("RETURN"), cancellable = true)
    private void sifucraft$pickSifuSurfaces(double distance, float partialTicks, boolean fluids,
            CallbackInfoReturnable<HitResult> cir) {
        Entity self = (Entity) (Object) this;
        SifuCollision.Active active = SifuCollision.active();
        if (active == null || !self.level().isClientSide() || self.level().dimension() != Level.OVERWORLD) return;
        Vec3 eye = self.getEyePosition(partialTicks);
        HitResult vanilla = cir.getReturnValue();
        Vec3 end = vanilla.getType() == HitResult.Type.MISS
                ? eye.add(self.getViewVector(partialTicks).scale(distance)) : vanilla.getLocation();
        BlockHitResult sifu = active.clip(eye, end);
        if (sifu != null) cir.setReturnValue(sifu);
    }

    /** Vanilla stopped the velocity on both axes (both moved less than asked): keep its part along the wall. */
    @Inject(method = "move", at = @At("TAIL"))
    private void sifucraft$afterMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        Vec3 tangent = sifucraft$tangent;
        if (tangent == null) return;
        sifucraft$tangent = null;
        double speed = sifucraft$velocity.x * tangent.x + sifucraft$velocity.z * tangent.z;
        if (speed <= 0) return;
        double factor = getBlockSpeedFactor();
        Entity self = (Entity) (Object) this;
        self.setDeltaMovement(new Vec3(tangent.x * speed * factor, self.getDeltaMovement().y, tangent.z * speed * factor));
    }
}
