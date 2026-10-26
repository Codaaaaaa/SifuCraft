package dev.sifucraft.client.mixin;

import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P7 POC keeps entities and particles; terrain continues through the existing native block path. */
@Mixin(ChunkSectionsToRender.class)
public abstract class WorldTerrainMixin {
    @Inject(method = {"renderGroup", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitTerrain(CallbackInfo ci) { if (WorldEffects.active()) ci.cancel(); }
}
