package dev.sifucraft.client.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;
import dev.sifucraft.client.SifuCraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Input from Sifu is applied right after Minecraft polls its own SDL events, every frame. */
@Mixin(SDLEventHandler.class)
public abstract class SDLEventHandlerMixin {
    @Inject(method = "pollEvents", at = @At("TAIL"))
    private void sifucraft$afterPoll(CallbackInfo ci) {
        SifuCraftClient.onFrame();
    }
}
