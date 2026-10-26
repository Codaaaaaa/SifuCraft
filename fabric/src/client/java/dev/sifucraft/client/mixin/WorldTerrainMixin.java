package dev.sifucraft.client.mixin;

import dev.sifucraft.client.WorldEffects;
import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep legacy terrain hidden until P8 has completed the scoped collision/model handover. */
@Mixin(ChunkSectionsToRender.class)
public abstract class WorldTerrainMixin {
    @Inject(method = {"renderGroup", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitTerrain(CallbackInfo ci) {
        if (WorldEffects.active() && !SifuCraftClient.blockModelsActive()) ci.cancel();
    }
}
