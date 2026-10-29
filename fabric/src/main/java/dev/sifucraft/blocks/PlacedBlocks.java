package dev.sifucraft.blocks;

import dev.sifucraft.storage.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The full solid blocks in the overworld above the dev world's ground: what Sifu shows in its
 * levels (each Sifu map sits in its own stretch of this world). Changed by the integrated server
 * whenever a block is set, read by the client to publish them to Sifu; kept with the save
 * (sifucraft-blocks.txt, one "x y z state" per line, the state as its numeric id).
 */
public final class PlacedBlocks {
    /** The superflat dev world's ground ends at y = -61: blocks below are the world's own. */
    public static final int MIN_Y = -60;
    private static final String FILE_NAME = "sifucraft-blocks.txt";

    private static final Map<Long, BlockState> BLOCKS = new ConcurrentHashMap<>();
    private static final AtomicInteger VERSION = new AtomicInteger();
    private static volatile Path file;
    private static volatile boolean dirty;
    private static long nextSaveNs;
    private static Consumer<String> log = message -> {};

    private PlacedBlocks() {}

    /** One block: where it is and what it is. */
    public record Placed(BlockPos pos, BlockState state) {}

    /** Loads the save at {@code worldRoot} (server start). */
    public static void open(Path worldRoot, Consumer<String> logger) {
        log = logger;
        BLOCKS.clear();
        dirty = false; nextSaveNs = 0;
        file = worldRoot.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length < 3) continue;
                    BlockState state = fields.length > 3 ? Block.stateById(Integer.parseInt(fields[3]))
                            : Blocks.STONE.defaultBlockState(); // a file from before states were kept
                    BLOCKS.put(BlockPos.asLong(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2])), state);
                }
            } catch (IOException | NumberFormatException e) {
                log.accept("could not read " + file + ": " + e);
            }
        }
        VERSION.incrementAndGet();
        log.accept(BLOCKS.size() + " placed blocks in " + file);
    }

    /** Saves and forgets the world (server stop). */
    public static void close() {
        save(true);
        file = null;
        dirty = false; nextSaveNs = 0;
        BLOCKS.clear();
        VERSION.incrementAndGet();
    }

    /** The block at {@code pos} is now {@code state}; only full solid blocks are kept. Server thread. */
    public static void changed(BlockPos pos, BlockState state, boolean fullSolid) {
        if (file == null || pos.getY() < MIN_Y) return;
        long key = pos.asLong();
        boolean changed = fullSolid ? BLOCKS.put(key, state) != state : BLOCKS.remove(key) != null;
        if (changed) {
            VERSION.incrementAndGet();
            dirty = true;
        }
    }

    /** Server thread; coalesce edits for at most one second, and retry failed writes. */
    public static void saveIfDirty() {
        save(false);
    }

    private static void save(boolean force) {
        Path target = file;
        long now = System.nanoTime();
        if (!dirty || target == null || !force && now < nextSaveNs) return;
        nextSaveNs = now + 1_000_000_000L;
        try {
            AtomicFiles.write(target, out -> {
                for (Map.Entry<Long, BlockState> block : BLOCKS.entrySet()) {
                    long key = block.getKey();
                    out.write(BlockPos.getX(key) + " " + BlockPos.getY(key) + " " + BlockPos.getZ(key) + " "
                            + Block.getId(block.getValue()));
                    out.newLine();
                }
            });
            dirty = false;
        } catch (IOException e) {
            log.accept("could not write " + target + ": " + e);
        }
    }

    /** Bumped on every change. */
    public static int version() {
        return VERSION.get();
    }

    /** The blocks with {@code minX <= x < maxX}. */
    public static List<Placed> between(int minX, int maxX) {
        List<Placed> out = new ArrayList<>();
        for (Map.Entry<Long, BlockState> block : BLOCKS.entrySet()) {
            int x = BlockPos.getX(block.getKey());
            if (x >= minX && x < maxX) out.add(new Placed(BlockPos.of(block.getKey()), block.getValue()));
        }
        return out;
    }
}
