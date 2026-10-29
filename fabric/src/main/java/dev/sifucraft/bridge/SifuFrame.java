package dev.sifucraft.bridge;

/** One consistent copy of the SifuSnapshot region (Unreal space: cm, Z up, degrees). */
public record SifuFrame(
        long frame,
        long unixMicros,
        int worldGeneration,
        int flags,
        Vec3 playerLocation,
        Vec3 playerRotation,
        Vec3 playerVelocity,
        Vec3 cameraLocation,
        Vec3 cameraRotation,
        float cameraFov,
        float health,
        float maxHealth,
        String worldPath,
        double mouseDx,
        double mouseDy,
        double scroll,
        int inputOwner,
        float capsuleHalfHeight,
        int anchorEpoch,
        String language) {

    public SifuFrame(long frame, long unixMicros, int worldGeneration, int flags, Vec3 playerLocation,
                     Vec3 playerRotation, Vec3 playerVelocity, Vec3 cameraLocation, Vec3 cameraRotation,
                     float cameraFov, float health, float maxHealth, String worldPath, double mouseDx, double mouseDy,
                     double scroll, int inputOwner, float capsuleHalfHeight, int anchorEpoch) {
        this(frame, unixMicros, worldGeneration, flags, playerLocation, playerRotation, playerVelocity, cameraLocation,
                cameraRotation, cameraFov, health, maxHealth, worldPath, mouseDx, mouseDy, scroll, inputOwner,
                capsuleHalfHeight, anchorEpoch, "");
    }

    /** x/y/z, or pitch/yaw/roll for rotations. */
    public record Vec3(float x, float y, float z) {
        public static final Vec3 ZERO = new Vec3(0, 0, 0);

        @Override
        public String toString() {
            return String.format("(%.0f, %.0f, %.0f)", x, y, z);
        }
    }

    public boolean hasPawn() {
        return (flags & Protocol.SNAPSHOT_HAS_PAWN) != 0;
    }

    public boolean hasCamera() {
        return (flags & Protocol.SNAPSHOT_HAS_CAMERA) != 0;
    }

    public boolean minecraftOwnsInput() {
        return inputOwner == Protocol.OWNER_MINECRAFT;
    }
}
