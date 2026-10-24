package dev.sifucraft.bridge;

/** Reply to one EnemyDamage event, already checked against both endpoint sessions. */
public record DamageResult(long eventId, int enemyId, int spawn, int result, float sifuDamage,
                           float healthAfter, int worldGeneration) {}
