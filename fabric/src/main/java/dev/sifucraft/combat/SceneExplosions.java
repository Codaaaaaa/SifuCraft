package dev.sifucraft.combat;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Original-map geometry blocks explosions without becoming destructible MC blocks. */
public final class SceneExplosions {
    private SceneExplosions() {}

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
