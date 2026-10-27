package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.SceneExplosions;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerExplosion.class)
public abstract class ExplosionSceneMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Unique private boolean sifucraft$evaluated;
    @Unique private int sifucraft$result;

    @WrapMethod(method = "explode")
    private int sifucraft$oneGeometryRevision(Operation<Integer> original) {
        if(sifucraft$evaluated)return sifucraft$result;
        sifucraft$evaluated=true;
        var explosion=(ServerExplosion)(Object)this;
        var source=explosion.getDirectSourceEntity();
        var id=source instanceof net.minecraft.world.entity.item.PrimedTnt
                ?java.util.UUID.nameUUIDFromBytes(("sifucraft:blast:"+source.getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                :java.util.UUID.randomUUID();
        var scene = SifuCollision.queryView();
        sifucraft$result=SceneExplosions.evaluate(explosion,id,()->scene == null || level.dimension() != Level.OVERWORLD ? original.call()
                : SifuCollision.withQueryView(scene, original::call));
        return sifucraft$result;
    }

    @WrapOperation(method="hurtEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;pushFromExplosion(Lnet/minecraft/world/phys/Vec3;)V"))
    private void sifucraft$originalImpulse(Entity entity,Vec3 impulse,Operation<Void> original){
        if(entity instanceof dev.sifucraft.combat.EnemyProxy proxy)SceneExplosions.impulse(proxy,impulse);
        else original.call(entity,impulse);
    }

    @WrapOperation(method = "getSeenPercent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult sifucraft$exposure(Level level, ClipContext ray,
                                                    Operation<BlockHitResult> original) {
        var hit = original.call(level, ray);
        return level.dimension() == Level.OVERWORLD
                ? SceneExplosions.exposureHit(SifuCollision.queryView(), ray.getFrom(), ray.getTo(), hit) : hit;
    }

    @WrapOperation(method = "calculateExplodedPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"))
    private Optional<Float> sifucraft$originalWall(ExplosionDamageCalculator calculator, Explosion explosion,
            BlockGetter getter, BlockPos pos, BlockState state, FluidState fluid, Operation<Optional<Float>> original,
            @Local(index = 6) double dx, @Local(index = 8) double dy, @Local(index = 10) double dz,
            @Local(index = 15) double x, @Local(index = 17) double y, @Local(index = 19) double z) {
        if (level.dimension() == Level.OVERWORLD && SceneExplosions.stopsDestructiveRay(
                SifuCollision.queryView(), center, x, y, z, dx, dy, dz)) return Optional.of(Float.MAX_VALUE);
        return original.call(calculator, explosion, getter, pos, state, fluid);
    }
}
