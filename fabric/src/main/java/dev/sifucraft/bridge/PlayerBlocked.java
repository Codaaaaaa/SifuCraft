package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reply to one original Sifu hit; native validates the original hit and its scope again. */
public record PlayerBlocked(long originHitId, int attackerId, int spawn, int worldGeneration,
                            int anchorEpoch, float blockedDamage, int flags) {
    public byte[] encode() {
        if (originHitId <= 0 || attackerId == 0 || spawn == 0 || worldGeneration == 0 || anchorEpoch == 0 ||
                !Float.isFinite(blockedDamage) || blockedDamage <= 0 || blockedDamage > 1_000_000 ||
                (flags & ~Protocol.BLOCKED_PERFECT) != 0) throw new IllegalArgumentException("invalid shield feedback");
        return ByteBuffer.allocate(Protocol.PLAYER_BLOCKED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(originHitId).putInt(attackerId).putInt(spawn).putInt(worldGeneration).putInt(anchorEpoch)
                .putFloat(blockedDamage).putInt(flags).array();
    }
}
