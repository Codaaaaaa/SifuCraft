package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.collision.SifuCollision;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Accept a proven imported solid face without installing a fake Minecraft block there. */
@Mixin(Projectile.class)
public abstract class WindChargeSceneHitMixin {
    @WrapOperation(method = "hitTargetOrDeflectSelf", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean sifucraft$sceneIsPhysical(BlockState state, Operation<Boolean> original,
                                              @Local(argsOnly = true) HitResult hit) {
        boolean empty = original.call(state);
        if (!empty || !((Object) this instanceof WindCharge charge) || charge.level().dimension() != Level.OVERWORLD ||
                !(hit instanceof BlockHitResult block)) return empty;
        var active = SifuCollision.active();
        if (active == null) return empty;
        var point = block.getLocation(); var face = block.getDirection();
        double dx = face.getStepX() * 1e-4, dy = face.getStepY() * 1e-4, dz = face.getStepZ() * 1e-4;
        boolean solidFace = active.solidAt(point.x - dx, point.y - dy, point.z - dz) &&
                !active.solidAt(point.x + dx, point.y + dy, point.z + dz);
        return !solidFace;
    }
}
