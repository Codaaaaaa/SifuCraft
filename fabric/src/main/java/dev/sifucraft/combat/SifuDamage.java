package dev.sifucraft.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/** Only bypasses item blocking; armor, absorption, hurt immunity and totems stay vanilla. */
final class SifuDamage {
    private static final ResourceKey<DamageType> UNBLOCKABLE = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath("sifucraft", "unblockable"));

    private SifuDamage() { }

    static DamageSource source(ServerPlayer player, EnemyProxy attacker, boolean canDefend) {
        if (!canDefend) return player.damageSources().source(UNBLOCKABLE, attacker);
        return attacker == null ? player.damageSources().generic() : player.damageSources().mobAttack(attacker);
    }
}
