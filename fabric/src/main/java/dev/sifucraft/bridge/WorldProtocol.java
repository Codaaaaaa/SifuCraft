package dev.sifucraft.bridge;

/** Paired world RGBA8 and raw D32F. Consumer reconstructs linear depth using the source projection. */
public final class WorldProtocol {
    private WorldProtocol() {}
    public static final int MAGIC = 0x57574653, VERSION = 1, MAX_WIDTH = 1920, MAX_HEIGHT = 1080, SLOTS = 3;
    public static final int PLANE_BYTES = MAX_WIDTH * MAX_HEIGHT * 4, SLOT_BYTES = 256 + 2 * PLANE_BYTES;
    public static final int MAPPING_BYTES = 64 + SLOTS * SLOT_BYTES, RGBA8_DEPTH32 = 1;
    public static final int FREE = 0, WRITING = 1, READY = 2, READING = 3, PREMULTIPLIED = 1, BOTTOM_UP = 2, ZERO_TO_ONE = 4;
    private static final int[] LAYOUT = {MAGIC, VERSION, MAX_WIDTH, MAX_HEIGHT, SLOTS, SLOT_BYTES, MAPPING_BYTES, RGBA8_DEPTH32,
            64, 256, 0, 4, 8, 12, 16, 24, 32, 36, 40, 44, 48, 60, 64, 128, 192,
            FREE, WRITING, READY, READING, PREMULTIPLIED, BOTTOM_UP, ZERO_TO_ONE};
    public static final long LAYOUT_HASH;
    static {
        long hash = 0xcbf29ce484222325L;
        for (int value : LAYOUT) for (int shift = 0; shift < 32; shift += 8) { hash ^= (value >>> shift) & 255; hash *= 0x100000001b3L; }
        LAYOUT_HASH = hash;
    }
    public static int planeBytes(int width, int height) { return OverlayProtocol.pixelBytes(width, height); }
    public static long slotOffset(int slot) {
        if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("world image slot");
        return 64L + (long)slot * SLOT_BYTES;
    }
    public static String mappingName(long mc, long sifu) {
        if (mc == 0 || sifu == 0) throw new IllegalArgumentException("world image sessions");
        return "Local\\SifuCraft.World.%016X.%016X".formatted(mc, sifu);
    }
}
