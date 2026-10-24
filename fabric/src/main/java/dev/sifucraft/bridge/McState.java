package dev.sifucraft.bridge;

/**
 * What the Minecraft side publishes each frame (Unreal space). {@link #NOT_READY} until the client
 * is in a world; pose fields are anchored to Sifu's {@code anchorEpoch}.
 */
public record McState(
        int flags,
        int perspective,
        SifuFrame.Vec3 playerLocation,
        SifuFrame.Vec3 playerRotation,
        SifuFrame.Vec3 playerVelocity,
        SifuFrame.Vec3 cameraLocation,
        SifuFrame.Vec3 cameraRotation,
        float cameraFov,
        int anchorEpoch) {

    public static final McState NOT_READY = new McState(0, 0, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO,
            SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, 0, 0);

    /** In a world, no pose yet. */
    public static McState ready() {
        return new McState(Protocol.MC_READY, 0, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO,
                SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, 0, 0);
    }
}
