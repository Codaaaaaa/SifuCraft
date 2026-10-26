package dev.sifucraft.bridge;

import java.nio.charset.StandardCharsets;

/** Independent complete collision snapshots; layouts mirror sifucraft_shapes.h. */
public final class ShapeProtocol {
    private ShapeProtocol() {}
    public static final int MAGIC = 0x48534653, VERSION = 1, SLOTS = 2, PAYLOAD_BYTES = 8*1024*1024;
    public static final int HEADER_BYTES = 128, FRAME_BYTES = 192, SECTION_BYTES = 32, BOX_BYTES = 24;
    public static final int SLOT_BYTES = FRAME_BYTES+PAYLOAD_BYTES, MAPPING_BYTES = HEADER_BYTES+SLOTS*SLOT_BYTES;
    public static final int MAX_SECTIONS = 8192, MAX_BOXES = 262144;
    public static final int FREE = 0, WRITING = 1, READY = 2, READING = 3;
    private static final int[] LAYOUT = {MAGIC,VERSION,MAPPING_BYTES,SLOT_BYTES,SLOTS,MAX_SECTIONS,MAX_BOXES,
        HEADER_BYTES,FRAME_BYTES,SECTION_BYTES,BOX_BYTES,0,4,8,16,24,32,40,48,56,60,64,72,80,128,192,
        FREE,WRITING,READY,READING};
    public static final long LAYOUT_HASH;
    static {
        long h = 0xcbf29ce484222325L;
        for (int value : LAYOUT) for (int shift = 0; shift < 32; shift += 8) { h ^= value >>> shift & 255; h *= 0x100000001b3L; }
        LAYOUT_HASH = h;
    }
    public static long hash(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte b : data) { h ^= b & 255; h *= 0x100000001b3L; }
        return h;
    }
    public static long mapHash(String map) { return hash(map.getBytes(StandardCharsets.UTF_8)); }
    public static String mappingName(long mc, long sifu) {
        if (mc == 0 || sifu == 0) throw new IllegalArgumentException("shape sessions");
        return "Local\\SifuCraft.Shapes.%016X.%016X".formatted(mc,sifu);
    }
}
