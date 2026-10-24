package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep Sifu's soundtrack while leaving Minecraft effects and saved volume settings intact. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Shadow public abstract void stopPlaying();
    @Unique private boolean sifucraft$suppressed;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sifucraft$musicTick(CallbackInfo ci) {
        boolean attached = SifuCraftClient.attachedToSifu();
        if (attached != sifucraft$suppressed) {
            sifucraft$suppressed = attached;
            if (attached) stopPlaying();
            LoggerFactory.getLogger("sifucraft").info("[audio] Minecraft background music {}", attached ? "suppressed while attached to Sifu" : "resumed after disconnect");
        }
        if (attached) ci.cancel();
    }

    @Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true)
    private void sifucraft$musicStart(Music music, CallbackInfo ci) {
        if (SifuCraftClient.attachedToSifu()) ci.cancel();
    }
}
