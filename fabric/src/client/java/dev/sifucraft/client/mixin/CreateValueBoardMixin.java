package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Create: the value board warps the hidden OS cursor onto the current value; follow it. */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.foundation.blockEntity.ValueSettingsScreen")
public abstract class CreateValueBoardMixin {
    @Inject(method = "setCursor(Lnet/minecraft/world/phys/Vec2;)V", at = @At("TAIL"), require = 0)
    private void sifucraft$followWarp(Vec2 at, CallbackInfo ci) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        SifuCraftClient.warpPointer(at.x * scale, at.y * scale);
    }
}
