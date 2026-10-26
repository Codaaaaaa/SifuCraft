package dev.sifucraft.client.mixin;

import dev.sifucraft.client.WorldTerrain;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Filter only solid models, keeping vanilla fluid tessellation and its neighbour queries intact. */
@Mixin(SectionCompiler.class)
public abstract class WorldTerrainMixin {
    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape sifucraft$solidModel(BlockState state) {
        return WorldTerrain.fluidsOnly() ? RenderShape.INVISIBLE : state.getRenderShape();
    }
    @Inject(method = "handleBlockEntity", at = @At("HEAD"), cancellable = true)
    private void sifucraft$solidBlockEntity(CallbackInfo ci) {
        if (WorldTerrain.fluidsOnly()) ci.cancel();
    }
}
