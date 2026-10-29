package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.combat.CombatHits;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads vanilla attack feedback; all damage/cooldown/enchantment calculations stay in Player. */
@Mixin(Player.class)
abstract class PlayerAttackMixin {
    @WrapMethod(method = "attack")
    private void sifucraft$attack(Entity target, Operation<Void> original) {
        CombatHits.beginAttack();
        try { original.call(target); }
        finally { CombatHits.endAttack(); }
    }

    @WrapMethod(method = "doSweepAttack")
    private void sifucraft$sweep(Entity target, float damage, DamageSource source, float strength, Operation<Void> original) {
        CombatHits.beginSweep();
        try { original.call(target, damage, source, strength); }
        finally { CombatHits.endSweep(); }
    }

    @Inject(method = "attackVisualEffects", at = @At("HEAD"))
    private void sifucraft$feedback(Entity target, boolean critical, boolean sweep, boolean strong,
                                   boolean piercing, float enchantmentDamage, CallbackInfo ci) {
        CombatHits.critical(target, critical);
    }
}
