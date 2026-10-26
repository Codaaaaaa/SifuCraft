package dev.sifucraft.client.mixin;

import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class WorldWeatherMixin {
    @Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitWeather(CallbackInfo ci) { if (WorldEffects.active()) ci.cancel(); }
}
