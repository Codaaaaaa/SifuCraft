package dev.sifucraft.mixin;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla mobs (iron golems) path over Sifu's floors: a solid Sifu cell is air to Minecraft's
 * block states, so pathfinding would otherwise see only empty space there. */
@Mixin(WalkNodeEvaluator.class)
public abstract class SifuPathingMixin {
    @Inject(method="getPathTypeFromState",at=@At("HEAD"),cancellable=true)
    private static void sifucraft$sifuCell(BlockGetter level,BlockPos pos,CallbackInfoReturnable<PathType> cir){
        var active=SifuCollision.active();
        // ponytail: any non-empty Sifu cell counts as a full block; partial-height floors path as full steps
        if(active!=null&&level.getBlockState(pos).isAir()&&!active.solidShapeAt(pos).isEmpty())cir.setReturnValue(PathType.BLOCKED);
    }
}
