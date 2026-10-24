package dev.sifucraft.bridge;

import java.util.Locale;

/** Byte layout mirrored from protocol/sifucraft_overlay.h; independent of the gameplay ABI. */
public final class OverlayProtocol {
    private OverlayProtocol() {}
    public static final int MAGIC = 0x564F4653;
    public static final int VERSION = 1;
    public static final int MAX_WIDTH = 1920, MAX_HEIGHT = 1080, SLOTS = 3;
    public static final int SLOT_BYTES = 64 + MAX_WIDTH * MAX_HEIGHT * 4;
    public static final int MAPPING_BYTES = 64 + SLOTS * SLOT_BYTES;
    public static final int RGBA8 = 1, PREMULTIPLIED = 1, BOTTOM_UP = 2;
    public static final int FREE = 0, WRITING = 1, READY = 2, READING = 3;
    public static final long LAYOUT_HASH;
    static {
        int[] values = {MAGIC, VERSION, MAX_WIDTH, MAX_HEIGHT, SLOTS, SLOT_BYTES, MAPPING_BYTES, RGBA8,
                64, 64, 0, 4, 8, 12, 16, 24, 28, 32, 36, FREE, WRITING, READY, READING, PREMULTIPLIED, BOTTOM_UP};
        long hash = 0xcbf29ce484222325L;
        for (int value : values) for (int shift = 0; shift < 32; shift += 8) {
            hash ^= (value >>> shift) & 255;
            hash *= 0x100000001b3L;
        }
        LAYOUT_HASH = hash;
    }
    public static String mappingName(long mcSession, long sifuSession) {
        if (mcSession == 0 || sifuSession == 0) throw new IllegalArgumentException("missing overlay session");
        return String.format(Locale.ROOT, "Local\\SifuCraft.Overlay.%016X.%016X", mcSession, sifuSession);
    }
    public static long slotOffset(int slot) { return 64L + (long) slot * SLOT_BYTES; }
    public static int pixelBytes(int width, int height) {
        if (width < 1 || width > MAX_WIDTH || height < 1 || height > MAX_HEIGHT)
            throw new IllegalArgumentException("overlay dimensions exceed 1920x1080");
        return width * height * 4;
    }
}
