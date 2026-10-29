package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Sifu routes the player's input here, the (hidden, unfocused) window counts as active. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow private boolean pause;
    @Redirect(method="runTick",at=@At(value="FIELD",target="Lnet/minecraft/client/Minecraft;pause:Z",opcode=org.objectweb.asm.Opcodes.PUTFIELD))
    private void sifucraft$pauseWithNativeWorld(Minecraft client,boolean vanilla){pause=vanilla||SifuCraftClient.worldPaused();}
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void sifucraft$nativeUseGesture(CallbackInfo ci) {
        if (SifuCraftClient.handleNativeUse()) ci.cancel();
    }
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void sifucraft$activeWhileRouted(CallbackInfoReturnable<Boolean> cir) {
        if (SifuCraftClient.minecraftOwnsInput()) cir.setReturnValue(true);
    }
}
