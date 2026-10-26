package dev.sifucraft.mixin;

import dev.sifucraft.collision.SifuCollision;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds Sifu's collision to Minecraft's block collision queries: once the vanilla blocks around the
 * query box are exhausted, the iterator yields the Sifu shapes there too. Every collision user goes
 * through this iterator (movement and step-up, on-ground and supporting block, noCollision, the
 * server's movement checks), so Minecraft's own physics runs unchanged against Sifu's level.
 * Suffocation checks are left out: being inside Sifu geometry should not hurt.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin<T> {
    @Shadow @Final private AABB box;
    @Shadow @Final private VoxelShape entityShape;
    @Shadow @Final private CollisionGetter collisionGetter;
    @Shadow @Final private boolean onlySuffocatingBlocks;
    @Shadow @Final private BlockPos.MutableBlockPos pos;
    @Shadow @Final private BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> resultProvider;

    @Unique private List<VoxelShape> sifucraft$shapes;
    @Unique private List<BlockPos> sifucraft$positions;
    @Unique private int sifucraft$next;

    @Inject(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockCollisions;endOfData()Ljava/lang/Object;"), cancellable = true)
    private void sifucraft$sifuShapes(CallbackInfoReturnable<T> cir) {
        if (sifucraft$shapes == null) {
            sifucraft$shapes = new ArrayList<>();
            sifucraft$positions = new ArrayList<>();
            SifuCollision.Active active = SifuCollision.queryView();
            if (active != null && !onlySuffocatingBlocks && collisionGetter instanceof Level level
                    && level.dimension() == Level.OVERWORLD) {
                active.forEachShape(box, (at, shape) -> {
                    // Like vanilla: only shapes that overlap the query box. Callers rely on it; the
                    // server's movement check treats any shape returned for the new position but
                    // not touching the old one as a new collision and snaps the player back.
                    if (!Shapes.joinIsNotEmpty(shape, entityShape, BooleanOp.AND)) return;
                    sifucraft$positions.add(at.immutable());
                    sifucraft$shapes.add(shape);
                });
            }
        }
        if (sifucraft$next < sifucraft$shapes.size()) {
            pos.set(sifucraft$positions.get(sifucraft$next));
            cir.setReturnValue(resultProvider.apply(pos, sifucraft$shapes.get(sifucraft$next++)));
        }
    }
}
