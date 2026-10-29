package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void sifucraft$background(GuiGraphicsExtractor gui, int x, int y, float partial, CallbackInfo ci) {
        if (!SifuCraftClient.mirroredScreenActive()) return;
        var window = Minecraft.getInstance().getWindow();
        gui.fill(0, 0, window.getGuiScaledWidth(), window.getGuiScaledHeight(), 0x60000000);
        ci.cancel();
    }
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void sifucraft$cursor(GuiGraphicsExtractor gui, int x, int y, float partial, CallbackInfo ci) {
        SifuCraftClient.drawPointer(gui);
    }
}
