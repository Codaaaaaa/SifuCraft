package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.collision.SifuCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ParticleUtils;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A mace smash on a Sifu floor raises its dust from a stand-in block: that floor is air to Minecraft,
 * and vanilla spawns no dust pillar from air. */
@Mixin(ParticleUtils.class)
public abstract class SmashParticlesMixin {
    @WrapOperation(method="spawnSmashAttackParticles",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/LevelAccessor;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState sifucraft$sifuFloor(LevelAccessor level,BlockPos pos,Operation<BlockState> original){
        var state=original.call(level,pos);var active=SifuCollision.active();
        // ponytail: one stone stand-in for every Sifu surface; map Native surface materials if one is ever exported
        return state.isAir()&&active!=null&&!active.solidShapeAt(pos).isEmpty()?Blocks.STONE.defaultBlockState():state;
    }
}
