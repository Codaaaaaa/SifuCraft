package dev.sifucraft.blocks;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Independent vanilla query results. Outline/support never become physical collision boxes. */
public record BlockGeometry(List<AABB> collision, List<AABB> outline, List<AABB> support) {
    public BlockGeometry {
        collision = List.copyOf(collision); outline = List.copyOf(outline); support = List.copyOf(support);
    }

    public static BlockGeometry at(BlockGetter world, BlockPos pos, BlockState state) {
        return new BlockGeometry(state.getCollisionShape(world, pos).toAabbs(),
                state.getShape(world, pos).toAabbs(), state.getBlockSupportShape(world, pos).toAabbs());
    }
}
