package dev.sifucraft.mixin;

import dev.sifucraft.combat.VanillaPlayerBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerPredictionMixin {
    @Inject(method="touch",at=@At("HEAD"),cancellable=true)
    private void sifucraft$noSpeculativePickup(Entity entity,CallbackInfo ci){
        if((Object)this instanceof VanillaPlayerBody)ci.cancel();
    }
}
