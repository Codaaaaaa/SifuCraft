package dev.sifucraft.client;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Add imported Sifu geometry to each of vanilla's eight third-person camera rays. */
public final class CameraCollision {
    private CameraCollision() {}

    public static BlockHitResult nearest(SifuCollision.Active scene, Vec3 from, Vec3 to, BlockHitResult vanilla) {
        if (scene == null) return vanilla;
        var sifu = scene.clip(from, to);
        return sifu != null && (vanilla.getType() == HitResult.Type.MISS ||
                from.distanceToSqr(sifu.getLocation()) < from.distanceToSqr(vanilla.getLocation())) ? sifu : vanilla;
    }
}
