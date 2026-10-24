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
        int anchorEpoch,
        long sifuSession,
        int worldGeneration,
        int guiInputAck) {

    public McState(int flags, int perspective, SifuFrame.Vec3 playerLocation, SifuFrame.Vec3 playerRotation,
                   SifuFrame.Vec3 playerVelocity, SifuFrame.Vec3 cameraLocation, SifuFrame.Vec3 cameraRotation,
                   float cameraFov, int anchorEpoch, long sifuSession, int worldGeneration) {
        this(flags, perspective, playerLocation, playerRotation, playerVelocity, cameraLocation, cameraRotation,
                cameraFov, anchorEpoch, sifuSession, worldGeneration, 0);
    }
    public McState withGui(boolean available, boolean open, int acknowledged) {
        int updated = flags & ~(Protocol.MC_GUI_AVAILABLE | Protocol.MC_GUI_OPEN);
        if (available) updated |= Protocol.MC_GUI_AVAILABLE;
        if (available && open) updated |= Protocol.MC_GUI_OPEN;
        return new McState(updated, perspective, playerLocation, playerRotation, playerVelocity, cameraLocation,
                cameraRotation, cameraFov, anchorEpoch, sifuSession, worldGeneration, acknowledged);
    }

    public static final McState NOT_READY = new McState(0, 0, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO,
            SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, 0, 0, 0, 0);

    /** In a world, no pose yet. */
    public static McState ready() {
        return new McState(Protocol.MC_READY, 0, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO,
                SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, 0, 0, 0, 0);
    }

    public static McState withoutPose(int flags, int epoch, long session, int world) {
        return new McState(flags & ~Protocol.MC_HAS_POSE, 0, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO,
                SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, 0, epoch, session, world);
    }
}
