package dev.sifucraft.collision;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Sifu's collision as a grid of 1/8-block cells (12.5 cm) in Unreal space, loaded from the grid
 * file sifu-native publishes ("SCVX", see sifu-native/src/collision/export.h). Blocks are 1 m cubes
 * aligned to the Unreal origin; cell (x, y, z) of a block is bit {@code x + 8y} of 64-bit layer
 * {@code z}. Immutable once read, so it can be shared between threads.
 */
public final class CollisionGrid {
    public static final int CELLS_PER_BLOCK = 8;
    private static final int VERSION = 1;
    private static final int KEY_BITS = 21;
    private static final long KEY_MASK = (1L << KEY_BITS) - 1;
    private static final long KEY_BIAS = 1L << (KEY_BITS - 1);

    private final String map;
    private final float cellCm;
    private final Map<Long, long[]> blocks;
    private final long cellCount;

    private CollisionGrid(String map, float cellCm, Map<Long, long[]> blocks, long cellCount) {
        this.map = map;
        this.cellCm = cellCm;
        this.blocks = blocks;
        this.cellCount = cellCount;
    }

    public static CollisionGrid read(Path path) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        in.get(magic);
        if (!"SCVX".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException(path + ": not a grid file");
        int version = in.getInt();
        if (version != VERSION) throw new IOException(path + ": grid file version " + version + ", expected " + VERSION);
        float cellCm = in.getFloat();
        int cellsPerBlock = in.getInt();
        if (cellsPerBlock != CELLS_PER_BLOCK) throw new IOException(path + ": " + cellsPerBlock + " cells per block");
        in.position(in.position() + 12); // pawn feet when the grid was built (debugging only)
        byte[] name = new byte[in.getInt()];
        in.get(name);
        int count = in.getInt();
        Map<Long, long[]> blocks = new HashMap<>(count * 2);
        long cells = 0;
        for (int i = 0; i < count; i++) {
            int bx = in.getInt(), by = in.getInt(), bz = in.getInt();
            long[] layers = new long[CELLS_PER_BLOCK];
            for (int z = 0; z < CELLS_PER_BLOCK; z++) {
                layers[z] = in.getLong();
                cells += Long.bitCount(layers[z]);
            }
            blocks.put(key(bx, by, bz), layers);
        }
        return new CollisionGrid(new String(name, StandardCharsets.UTF_8), cellCm, blocks, cells);
    }

    /** Whether Unreal-space cell (x, y, z) is solid (cell = floor(cm / cellCm)). */
    public boolean solid(int x, int y, int z) {
        long[] layers = blocks.get(key(Math.floorDiv(x, CELLS_PER_BLOCK), Math.floorDiv(y, CELLS_PER_BLOCK),
                Math.floorDiv(z, CELLS_PER_BLOCK)));
        if (layers == null) return false;
        int bit = Math.floorMod(x, CELLS_PER_BLOCK) + CELLS_PER_BLOCK * Math.floorMod(y, CELLS_PER_BLOCK);
        return (layers[Math.floorMod(z, CELLS_PER_BLOCK)] >>> bit & 1) != 0;
    }

    /**
     * The top (Unreal cm) of the highest solid cell under a square footprint centred on (x, y) with
     * the given half width, among cells whose top lies between {@code feetZ - maxDropCm} and
     * {@code feetZ + maxRiseCm}; NaN if there is none. Used to stand a player on the grid's floor.
     */
    public double floorUnder(double x, double y, double feetZ, double halfWidthCm, double maxRiseCm, double maxDropCm) {
        int x0 = cell(x - halfWidthCm), x1 = cell(x + halfWidthCm);
        int y0 = cell(y - halfWidthCm), y1 = cell(y + halfWidthCm);
        int zTop = cell(feetZ + maxRiseCm) - 1, zBottom = cell(feetZ - maxDropCm) - 1;
        for (int z = zTop; z >= zBottom; z--) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cx = x0; cx <= x1; cx++) {
                    if (solid(cx, cy, z)) return (z + 1) * (double) cellCm;
                }
            }
        }
        return Double.NaN;
    }

    private int cell(double cm) {
        return (int) Math.floor(cm / cellCm);
    }

    /** The 8 layers of Unreal-space block (bx, by, bz), or null if it holds nothing solid. */
    public long[] block(int bx, int by, int bz) {
        return blocks.get(key(bx, by, bz));
    }

    public String map() {
        return map;
    }

    public float cellCm() {
        return cellCm;
    }

    public int blockCount() {
        return blocks.size();
    }

    public long cellCount() {
        return cellCount;
    }

    static long key(int bx, int by, int bz) {
        return ((bx + KEY_BIAS) & KEY_MASK) | ((by + KEY_BIAS) & KEY_MASK) << KEY_BITS
                | ((bz + KEY_BIAS) & KEY_MASK) << (2 * KEY_BITS);
    }
}
