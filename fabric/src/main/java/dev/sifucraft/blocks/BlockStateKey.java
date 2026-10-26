package dev.sifucraft.blocks;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Stable registry names and properties; numeric state ids are only session-local caches. */
public final class BlockStateKey {
    private BlockStateKey() {}

    public static String encode(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    public static BlockState decode(String text) {
        var reader = new StringReader(text);
        try {
            var result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false);
            if (reader.canRead()) throw new IllegalArgumentException("Trailing block state data: " + text);
            return result.blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Invalid block state: " + text, e);
        }
    }
}
