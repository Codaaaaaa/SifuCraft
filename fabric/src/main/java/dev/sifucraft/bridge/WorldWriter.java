package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldProtocol.*;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/** One producer; readers must claim READY before inspecting either plane or the camera. */
public final class WorldWriter implements AutoCloseable {
    public record Camera(float x, float y, float z, float[] projection, float[] viewRotation) {
        public Camera {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || projection.length != 16 || viewRotation.length != 16)
                throw new IllegalArgumentException("world camera");
            projection = projection.clone(); viewRotation = viewRotation.clone();
            for (float v : projection) if (!Float.isFinite(v)) throw new IllegalArgumentException("world projection");
            for (float v : viewRotation) if (!Float.isFinite(v)) throw new IllegalArgumentException("world view");
            if (Math.abs(projection[0]) < .0001f || Math.abs(projection[5]) < .0001f || Math.abs(projection[11]) < .0001f ||
                    Math.abs(viewRotation[15] - 1) > .0001f) throw new IllegalArgumentException("world camera matrices");
        }
        @Override public float[] projection() { return projection.clone(); }
        @Override public float[] viewRotation() { return viewRotation.clone(); }
    }
    private static final VarHandle INT = JAVA_INT.varHandle();
    private final SharedMapping mapping;
    private final MemorySegment memory;
    private int next;
    private boolean closed;
    public static long clockMillis() { return Win32.tickCount64(); }
    public WorldWriter(long mc, long sifu) {
        mapping = SharedMapping.open(mappingName(mc, sifu), MAPPING_BYTES); memory = mapping.segment();
        try { initialize(mc, sifu); } catch (RuntimeException e) { mapping.close(); throw e; }
    }
    WorldWriter(MemorySegment memory, long mc, long sifu) { this.memory = memory; mapping = null; initialize(mc, sifu); }
    private void initialize(long mc, long sifu) {
        mappingName(mc, sifu);
        if (memory.byteSize() != MAPPING_BYTES) throw new IllegalArgumentException("world image mapping size");
        int magic = (int)INT.getAcquire(memory, 0L);
        if (magic != 0) {
            if (magic != MAGIC || memory.get(JAVA_INT, 4) != VERSION || memory.get(JAVA_INT, 8) != MAPPING_BYTES ||
                    memory.get(JAVA_INT, 12) != MAX_WIDTH || memory.get(JAVA_INT, 16) != MAX_HEIGHT ||
                    memory.get(JAVA_INT, 20) != SLOT_BYTES || memory.get(JAVA_INT, 24) != SLOTS || memory.get(JAVA_INT, 28) != RGBA8_DEPTH32 ||
                    memory.get(JAVA_LONG, 32) != mc || memory.get(JAVA_LONG, 40) != sifu || memory.get(JAVA_LONG, 48) != LAYOUT_HASH)
                throw new IllegalArgumentException("incompatible world mapping");
            return;
        }
        memory.fill((byte)0);
        memory.set(JAVA_INT, 4, VERSION); memory.set(JAVA_INT, 8, MAPPING_BYTES);
        memory.set(JAVA_INT, 12, MAX_WIDTH); memory.set(JAVA_INT, 16, MAX_HEIGHT); memory.set(JAVA_INT, 20, SLOT_BYTES);
        memory.set(JAVA_INT, 24, SLOTS); memory.set(JAVA_INT, 28, RGBA8_DEPTH32);
        memory.set(JAVA_LONG, 32, mc); memory.set(JAVA_LONG, 40, sifu); memory.set(JAVA_LONG, 48, LAYOUT_HASH);
        INT.setRelease(memory, 0L, MAGIC);
    }
    public boolean publish(MemorySegment pair, int width, int height, long frame, long capturedMs, int world, int epoch,
                           long sifuFrame, Camera camera, boolean zeroToOne) {
        if (closed) return false;
        int plane = planeBytes(width, height);
        if (pair.byteSize() != plane * 2L || frame <= 0 || capturedMs <= 0 || world == 0 || epoch == 0 || sifuFrame <= 0 || camera == null)
            throw new IllegalArgumentException("world image frame");
        for (int attempt = 0; attempt < SLOTS; ++attempt) {
            int slot = next; next = (next + 1) % SLOTS; long at = slotOffset(slot);
            int state = (int)INT.getAcquire(memory, at);
            if ((state != FREE && state != READY) || !INT.compareAndSet(memory, at, state, WRITING)) continue;
            try {
                memory.set(JAVA_INT, at + 4, width); memory.set(JAVA_INT, at + 8, height); memory.set(JAVA_INT, at + 12, width * 4);
                memory.set(JAVA_LONG, at + 16, frame); memory.set(JAVA_LONG, at + 24, capturedMs);
                memory.set(JAVA_INT, at + 32, world); memory.set(JAVA_INT, at + 36, epoch);
                memory.set(JAVA_INT, at + 40, PREMULTIPLIED | BOTTOM_UP | (zeroToOne ? ZERO_TO_ONE : 0));
                memory.set(JAVA_INT, at + 44, plane * 2);
                memory.set(JAVA_FLOAT, at + 48, camera.x); memory.set(JAVA_FLOAT, at + 52, camera.y); memory.set(JAVA_FLOAT, at + 56, camera.z);
                memory.set(JAVA_FLOAT, at + 60, 100);
                for (int i = 0; i < 16; ++i) {
                    memory.set(JAVA_FLOAT, at + 64 + i * 4, camera.projection[i]);
                    memory.set(JAVA_FLOAT, at + 128 + i * 4, camera.viewRotation[i]);
                }
                memory.set(JAVA_LONG, at + 192, sifuFrame);
                MemorySegment.copy(pair, 0, memory, at + 256, plane * 2L);
                INT.setRelease(memory, at, READY); return true;
            } catch (RuntimeException e) { INT.setRelease(memory, at, FREE); throw e; }
        }
        return false;
    }
    @Override public void close() { if (!closed) { closed = true; if (mapping != null) mapping.close(); } }
}
