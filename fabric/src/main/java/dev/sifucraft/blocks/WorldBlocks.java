package dev.sifucraft.blocks;

import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** P8 derived index. Vanilla chunk/BlockEntity NBT remains the only saved world truth. */
public final class WorldBlocks {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("sifucraft.blockStates","true"));
    private static volatile BlockSectionIndex index;
    private WorldBlocks() {}
    public static void open() { if (ENABLED) index = new BlockSectionIndex(); }
    public static void close() { index = null; }
    public static BlockSectionIndex index() { return index; }

    public static void changed(BlockPos pos, BlockState state) {
        var current = index;
        if (current != null && pos.getY() >= PlacedBlocks.MIN_Y) current.changed(pos, state);
    }

    public static void loaded(ServerLevel level, LevelChunk chunk) {
        var current = index;
        if (current == null || level.dimension() != Level.OVERWORLD) return;
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = level.getMinSectionY() + i;
            if ((sectionY << 4) + 15 < PlacedBlocks.MIN_Y) continue;
            var states = new HashMap<Short, BlockState>();
            var section = sections[i];
            if (!section.hasOnlyAir()) {
                var pos = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) {
                    if ((sectionY << 4) + y < PlacedBlocks.MIN_Y) continue;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        if (!state.isAir()) states.put(SectionPos.sectionRelativePos(pos.set(x, y, z)), state);
                    }
                }
            }
            current.replace(SectionPos.of(chunk.getPos(), sectionY).asLong(), states);
        }
    }

    public static void unloaded(ServerLevel level, LevelChunk chunk) {
        var current = index;
        if (current != null && level.dimension() == Level.OVERWORLD)
            current.unloadChunk(chunk.getPos().x(), chunk.getPos().z());
    }
}
