package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.SceneExplosions;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkSceneMixin {
    @WrapOperation(method="dealExplosionDamage",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult sifucraft$exposure(Level level,ClipContext ray,Operation<BlockHitResult> original){
        var hit=original.call(level,ray);return level.dimension()==Level.OVERWORLD?SceneExplosions.exposureHit(SifuCollision.queryView(),ray.getFrom(),ray.getTo(),hit):hit;
    }
}
