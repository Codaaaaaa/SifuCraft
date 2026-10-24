package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While Sifu routes the player's input here, the (hidden, unfocused) window counts as active. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void sifucraft$activeWhileRouted(CallbackInfoReturnable<Boolean> cir) {
        if (SifuCraftClient.minecraftOwnsInput()) cir.setReturnValue(true);
    }
}
