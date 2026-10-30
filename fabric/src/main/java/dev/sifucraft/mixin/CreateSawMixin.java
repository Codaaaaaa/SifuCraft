package dev.sifucraft.mixin;

import dev.sifucraft.combat.CreateSaws;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Create: a moving saw's damage pass names its own block, so a Sifu NPC proxy's hurt has proof. */
@Pseudo
@Mixin(targets = "com.zurrtum.create.content.kinetics.base.BlockBreakingMovementBehaviour")
public abstract class CreateSawMixin {
    @Inject(method = "damageEntities", at = @At("HEAD"), require = 0)
    private void sifucraft$sawProof(@org.spongepowered.asm.mixin.injection.Coerce Object context, BlockPos pos, Level level, CallbackInfo ci) { CreateSaws.contraption(pos); }
    @Inject(method = "damageEntities", at = @At("RETURN"), require = 0)
    private void sifucraft$sawProofDone(@org.spongepowered.asm.mixin.injection.Coerce Object context, BlockPos pos, Level level, CallbackInfo ci) { CreateSaws.contraption(null); }
}
