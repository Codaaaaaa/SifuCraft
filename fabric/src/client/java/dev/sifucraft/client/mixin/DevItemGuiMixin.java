package dev.sifucraft.client.mixin;

import dev.sifucraft.client.DevItemInventoryScreen;
import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Gui.class)
abstract class DevItemGuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen sifucraft$devCatalog(Screen screen) {
        return screen != null && screen.getClass() == InventoryScreen.class && SifuCraftClient.devItemsAvailable()
                ? new DevItemInventoryScreen(Minecraft.getInstance().player) : screen;
    }
}
