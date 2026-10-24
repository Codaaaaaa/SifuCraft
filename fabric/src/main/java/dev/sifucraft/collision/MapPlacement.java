package dev.sifucraft.collision;

/**
 * Where one Sifu map sits in the Minecraft world: Minecraft block coordinates are Unreal metres
 * plus a whole-block origin, so Sifu's 1/8-block collision cells land exactly on Minecraft's 1/8
 * grid. Axes: {@code MC.x = UE.x}, {@code MC.y = UE.z}, {@code MC.z = UE.y}; swapping two axes also
 * converts Unreal's left-handed frame to Minecraft's right-handed one.
 */
public record MapPlacement(String map, int originX, int originY, int originZ) {
    public static final double CM_PER_BLOCK = 100.0;

    public double mcX(double ueX) {
        return ueX / CM_PER_BLOCK + originX;
    }

    public double mcY(double ueZ) {
        return ueZ / CM_PER_BLOCK + originY;
    }

    public double mcZ(double ueY) {
        return ueY / CM_PER_BLOCK + originZ;
    }

    public double ueX(double mcX) {
        return (mcX - originX) * CM_PER_BLOCK;
    }

    public double ueY(double mcZ) {
        return (mcZ - originZ) * CM_PER_BLOCK;
    }

    public double ueZ(double mcY) {
        return (mcY - originY) * CM_PER_BLOCK;
    }
}
