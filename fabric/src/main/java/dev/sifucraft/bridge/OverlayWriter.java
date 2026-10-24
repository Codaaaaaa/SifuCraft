package dev.sifucraft.bridge;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static dev.sifucraft.bridge.OverlayProtocol.*;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/** Nonblocking latest-frame mailbox. Runs only on the Minecraft render thread. */
public final class OverlayWriter implements AutoCloseable {
    private static final VarHandle INT = JAVA_INT.varHandle();
    private static final VarHandle LONG = JAVA_LONG.varHandle();
    private final MemorySegment memory;
    private final SharedMapping mapping;
    private int nextSlot;
    private boolean closed;

    public OverlayWriter(long mcSession, long sifuSession) {
        mapping = SharedMapping.open(mappingName(mcSession, sifuSession), MAPPING_BYTES);
        memory = mapping.segment();
        try { initialize(memory, mcSession, sifuSession); }
        catch (RuntimeException e) { mapping.close(); throw e; }
    }

    // Also used with an aligned confined arena by the protocol tests.
    OverlayWriter(MemorySegment memory, long mcSession, long sifuSession) {
        this.memory = memory;
        mapping = null;
        initialize(memory, mcSession, sifuSession);
    }

    private static void initialize(MemorySegment memory, long mcSession, long sifuSession) {
        mappingName(mcSession, sifuSession); // validate even for an in-memory fixture
        if (memory.byteSize() != MAPPING_BYTES) throw new IllegalArgumentException("overlay mapping size");
        int magic = (int) INT.getAcquire(memory, 0L);
        if (magic != 0) {
            if (magic != MAGIC || memory.get(JAVA_INT, 4) != VERSION ||
                    memory.get(JAVA_INT, 8) != MAPPING_BYTES || memory.get(JAVA_LONG, 48) != LAYOUT_HASH ||
                    memory.get(JAVA_LONG, 32) != mcSession || memory.get(JAVA_LONG, 40) != sifuSession)
                throw new IllegalArgumentException("incompatible overlay mapping");
            return;
        }
        memory.fill((byte) 0);
        memory.set(JAVA_INT, 4, VERSION);
        memory.set(JAVA_INT, 8, MAPPING_BYTES);
        memory.set(JAVA_INT, 12, MAX_WIDTH);
        memory.set(JAVA_INT, 16, MAX_HEIGHT);
        memory.set(JAVA_INT, 20, SLOT_BYTES);
        memory.set(JAVA_INT, 24, SLOTS);
        memory.set(JAVA_INT, 28, RGBA8);
        memory.set(JAVA_LONG, 32, mcSession);
        memory.set(JAVA_LONG, 40, sifuSession);
        memory.set(JAVA_LONG, 48, LAYOUT_HASH);
        INT.setRelease(memory, 0L, MAGIC); // publish immutable header last
    }

    public boolean publish(MemorySegment pixels, int width, int height, long frameId, int world, int epoch, boolean bottomUp) {
        return publish(pixels, width, height, frameId, world, epoch, bottomUp, 0, 0, 0, 0, 0);
    }

    public long viewport() { return closed ? 0 : (long) LONG.getAcquire(memory, 56L); }

    public boolean publish(MemorySegment pixels, int width, int height, long frameId, int world, int epoch, boolean bottomUp,
                           int left, int top, int right, int bottom, int guiScale) {
        if (closed) return false;
        int bytes = pixelBytes(width, height);
        if (pixels.byteSize() != bytes || frameId <= 0 || world == 0 || epoch == 0)
            throw new IllegalArgumentException("invalid overlay frame");
        if (left < 0 || top < 0 || right < left || right > width || bottom < top || bottom > height || guiScale < 0 || guiScale > 16)
            throw new IllegalArgumentException("invalid overlay GUI bounds");
        for (int attempt = 0; attempt < SLOTS; ++attempt) {
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % SLOTS;
            long offset = slotOffset(slot);
            int state = (int) INT.getAcquire(memory, offset);
            if ((state != FREE && state != READY) || !INT.compareAndSet(memory, offset, state, WRITING)) continue;
            try {
                memory.set(JAVA_INT, offset + 4, width);
                memory.set(JAVA_INT, offset + 8, height);
                memory.set(JAVA_INT, offset + 12, width * 4);
                memory.set(JAVA_LONG, offset + 16, frameId);
                memory.set(JAVA_INT, offset + 24, world);
                memory.set(JAVA_INT, offset + 28, epoch);
                memory.set(JAVA_INT, offset + 32, PREMULTIPLIED | (bottomUp ? BOTTOM_UP : 0));
                memory.set(JAVA_INT, offset + 36, bytes);
                memory.set(JAVA_INT, offset + 40, left);
                memory.set(JAVA_INT, offset + 44, top);
                memory.set(JAVA_INT, offset + 48, right);
                memory.set(JAVA_INT, offset + 52, bottom);
                memory.set(JAVA_INT, offset + 56, guiScale);
                MemorySegment.copy(pixels, 0, memory, offset + 64, bytes);
                INT.setRelease(memory, offset, READY);
                return true;
            } catch (RuntimeException e) {
                INT.setRelease(memory, offset, FREE);
                throw e;
            }
        }
        return false;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (mapping != null) mapping.close();
    }
}
