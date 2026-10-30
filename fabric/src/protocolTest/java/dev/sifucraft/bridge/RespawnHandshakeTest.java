package dev.sifucraft.bridge;

final class RespawnHandshakeTest {
    private static SifuFrame frame(int world, int epoch) {
        var zero = SifuFrame.Vec3.ZERO;
        return new SifuFrame(1, 0, world, Protocol.SNAPSHOT_HAS_PAWN, zero, zero, zero, zero, zero,
                75, 180, 180, "/Game/Test.Test", 0, 0, 0, Protocol.OWNER_MINECRAFT, 92, epoch);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static void run() {
        var state = new RespawnHandshake();
        var old = frame(3, 7);
        check(state.tick(100, true, 1, old, true, true, true) == RespawnHandshake.Action.DIED &&
                !state.ready() && !state.mayPlace() && (state.flags() & Protocol.MC_DEAD) != 0, "death immediately suspends poses");
        check(state.tick(1099, true, 1, old, true, true, false) == RespawnHandshake.Action.NONE, "death waits a full second");
        check(state.tick(1100, true, 1, old, true, true, false) == RespawnHandshake.Action.RESPAWN, "one-second respawn request");
        check(state.tick(1150, true, 1, old, true, true, false) == RespawnHandshake.Action.NONE, "respawn packet is not sent every frame");
        check(state.tick(3100, true, 1, old, true, true, false) == RespawnHandshake.Action.RESPAWN, "lost respawn request has a bounded retry");
        check(state.tick(3120, true, 1, old, false, false, false) == RespawnHandshake.Action.NONE && !state.ready(), "replaced LocalPlayer may be temporarily absent");
        check(state.tick(3150, true, 1, old, true, false, false) == RespawnHandshake.Action.REQUEST &&
                state.requestEpoch() == 7 && !state.mayPlace() && (state.flags() & Protocol.MC_REQUEST_ANCHOR) != 0,
                "respawn requests a fresh native epoch before teleporting");
        check(state.tick(3200, true, 1, old, true, false, true) == RespawnHandshake.Action.NONE && !state.ready(), "old anchor never completes respawn");
        check(state.tick(3210, false, 0, null, true, false, false) == RespawnHandshake.Action.NONE, "disconnect preserves pending recovery");
        check(state.tick(3300, true, 2, frame(3, 8), true, false, false) == RespawnHandshake.Action.REQUEST && state.requestEpoch() == 8,
                "new session cannot be mistaken for the old request's acknowledgement");
        check(state.tick(3400, true, 2, frame(4, 9), true, false, false) == RespawnHandshake.Action.REQUEST && state.requestEpoch() == 9,
                "map transition reissues request in the new world");
        check(state.tick(3500, true, 2, frame(4, 10), true, false, false) == RespawnHandshake.Action.ACKNOWLEDGED && state.mayPlace() && !state.ready(),
                "new native epoch allows placement, but still no pose");
        check(state.tick(3600, true, 2, frame(4, 10), true, false, true) == RespawnHandshake.Action.LANDED && state.ready(), "landing resumes the new life");
        var suspended = McState.withoutPose(state.flags(), 10, 2, 4);
        check((suspended.flags() & Protocol.MC_HAS_POSE) == 0 && suspended.sifuSession() == 2 && suspended.worldGeneration() == 4,
                "suspended snapshots retain the target lifetime/world and contain no pose");
        var clicked = new RespawnHandshake();
        check(clicked.tick(100, true, 2, frame(4, 10), true, true, true, true) == RespawnHandshake.Action.NONE && clicked.ready(),
                "death screen keeps the pose and GUI while it waits for a click");
        check(clicked.tick(60000, true, 2, frame(4, 10), true, true, true, true) == RespawnHandshake.Action.NONE, "no automatic respawn behind the death screen");
        check(clicked.tick(60100, true, 2, frame(4, 10), true, false, false, false) == RespawnHandshake.Action.REQUEST &&
                clicked.requestEpoch() == 10 && !clicked.ready(), "the clicked respawn re-anchors like an automatic one");
        var leftScreen = new RespawnHandshake();
        leftScreen.tick(100, true, 2, frame(4, 10), true, true, true, true);
        check(leftScreen.tick(200, true, 2, frame(4, 10), true, true, true, false) == RespawnHandshake.Action.DIED,
                "leaving Minecraft's view falls back to the automatic respawn");
    }
}
