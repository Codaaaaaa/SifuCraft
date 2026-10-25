package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One horizontal range push, authorized by an actually applied smash damage event. */
public record EnemyImpulse(long originDamageId, int enemyId, int spawn, int worldGeneration,
                           int anchorEpoch, float x, float z) {
    public byte[] encode() {
        if (originDamageId <= 0 || enemyId == 0 || spawn == 0 || worldGeneration == 0 || anchorEpoch == 0 ||
                !Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x) > 16 || Math.abs(z) > 16 || Math.hypot(x, z) <= 1e-6)
            throw new IllegalArgumentException("invalid enemy impulse");
        return ByteBuffer.allocate(Protocol.ENEMY_IMPULSE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(originDamageId).putInt(enemyId).putInt(spawn).putInt(worldGeneration).putInt(anchorEpoch)
                .putFloat(x).putFloat(z).array();
    }
}
