package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable scoped Sifu hit; the transport supplies source session and event ID separately. */
public record PlayerHit(int attackerId, int spawn, float sifuDamage, int flags, int worldGeneration,
                        int anchorEpoch, float dirX, float dirY) {
    public int attackPower() { return (flags >>> 8) & 255; }
    public static PlayerHit decode(byte[] payload) {
        if (payload.length != Protocol.PlayerHit.SIZE) throw new IllegalArgumentException("player hit payload size");
        var b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        var hit = new PlayerHit(b.getInt(), b.getInt(), b.getFloat(), b.getInt(), b.getInt(), b.getInt(), b.getFloat(), b.getFloat());
        if (!Float.isFinite(hit.sifuDamage) || hit.sifuDamage <= 0 || hit.sifuDamage > 1000000 ||
                !Float.isFinite(hit.dirX) || !Float.isFinite(hit.dirY) || (hit.flags & ~0xff07) != 0 ||
                hit.worldGeneration == 0 || hit.anchorEpoch == 0 || ((hit.attackerId == 0) != (hit.spawn == 0))) {
            throw new IllegalArgumentException("invalid player hit fields");
        }
        return hit;
    }
}
