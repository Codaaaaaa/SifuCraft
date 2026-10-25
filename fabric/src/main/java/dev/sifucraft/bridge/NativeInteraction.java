package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Read-only native candidate; the receiver never treats it as permission to bypass native validation. */
public record NativeInteraction(long session, long revision, int world, int epoch, int flags, int target, int serial,
        SifuFrame.Vec3 minimum, SifuFrame.Vec3 maximum, float rangeCm, long receivedMs) {
    public static NativeInteraction decode(long session, byte[] bytes, long now) {
        if (bytes.length != Protocol.INTERACTION_SIZE) throw new IllegalArgumentException("interaction snapshot size");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long revision = b.getLong(16); int world = b.getInt(24), epoch = b.getInt(28), flags = b.getInt(32);
        int target = b.getInt(36), serial = b.getInt(40);
        var lo = new SifuFrame.Vec3(b.getFloat(56), b.getFloat(60), b.getFloat(64));
        var hi = new SifuFrame.Vec3(b.getFloat(68), b.getFloat(72), b.getFloat(76));
        float range = b.getFloat(92);
        if ((flags & ~Protocol.INTERACT_AVAILABLE) != 0) throw new IllegalArgumentException("interaction flags");
        if (flags != 0 && (session == 0 || revision == 0 || world == 0 || epoch == 0 || target == 0 || serial <= 0 ||
                !finite(lo) || !finite(hi) || lo.x() > hi.x() || lo.y() > hi.y() || lo.z() > hi.z() ||
                !Float.isFinite(range) || range <= 0 || range > 1000)) throw new IllegalArgumentException("interaction candidate");
        return new NativeInteraction(session, revision, world, epoch, flags, target, serial, lo, hi, range, now);
    }
    private static boolean finite(SifuFrame.Vec3 p) {
        return Float.isFinite(p.x()) && Float.isFinite(p.y()) && Float.isFinite(p.z()) &&
                Math.abs(p.x()) <= 1e7 && Math.abs(p.y()) <= 1e7 && Math.abs(p.z()) <= 1e7;
    }
    public boolean available(long session, SifuFrame frame, long now) {
        return (flags & Protocol.INTERACT_AVAILABLE) != 0 && this.session == session && frame != null && frame.minecraftOwnsInput() &&
                world == frame.worldGeneration() && epoch == frame.anchorEpoch() && now >= receivedMs && now - receivedMs <= 250;
    }
    public byte[] request() {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putLong(revision).putInt(target).putInt(serial)
                .putInt(world).putInt(epoch).putLong(0).array();
    }
    public record Result(long request, long revision, int outcome, int target, int world, int epoch) {
        public static Result decode(byte[] bytes) {
            if (bytes.length != 32) throw new IllegalArgumentException("interaction result size");
            var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Result(b.getLong(), b.getLong(), b.getInt(), b.getInt(), b.getInt(), b.getInt());
        }
    }
}
