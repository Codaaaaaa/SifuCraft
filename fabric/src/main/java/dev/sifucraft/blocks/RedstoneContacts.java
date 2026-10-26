package dev.sifucraft.blocks;

import dev.sifucraft.combat.EnemyProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Signal contacts for freshly mirrored NPCs; vanilla owns filtering, strength and release ticks. */
public final class RedstoneContacts {
    private RedstoneContacts() {}
    public static void touch(EnemyProxy proxy) {
        if(!WorldBlocks.ENABLED || !(proxy.level() instanceof ServerLevel level) || proxy.isRemoved() || !proxy.isAlive() ||
            proxy.sifuKey().id()<0 || proxy.sifuKey().spawn()==0)return;
        touchBody(proxy);
    }
    static void touchBody(Entity body) {
        if(!WorldBlocks.ENABLED || !(body.level() instanceof ServerLevel level) || body.isRemoved() || !body.isAlive())return;
        var box=body.getBoundingBox().deflate(1.0E-7);var entityShape=Shapes.create(box);
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ))) {
            if(!level.hasChunkAt(pos))continue;
            var state=level.getBlockState(pos);
            if(!(state.getBlock() instanceof BasePressurePlateBlock || state.getBlock() instanceof TripWireBlock))continue;
            var inside=state.getEntityInsideCollisionShape(level,pos,body).move(pos.getX(),pos.getY(),pos.getZ());
            if(Shapes.joinIsNotEmpty(entityShape,inside,BooleanOp.AND))
                state.entityInside(level,pos,body,InsideBlockEffectApplier.NOOP,false);
        }
    }
}
