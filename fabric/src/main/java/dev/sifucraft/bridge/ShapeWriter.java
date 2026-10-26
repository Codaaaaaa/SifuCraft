package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.ShapeProtocol.*;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/** Single client-thread publisher; never overwrites a slot claimed by Native. */
public final class ShapeWriter implements AutoCloseable {
    private static final VarHandle INT = JAVA_INT.varHandle(), LONG = JAVA_LONG.varHandle();
    private final SharedMapping mapping;
    private final MemorySegment memory;
    private int next;
    public ShapeWriter(long mc, long sifu) {
        mapping = SharedMapping.open(mappingName(mc,sifu),MAPPING_BYTES); memory = mapping.segment();
        try { initialize(mc,sifu); } catch (RuntimeException e) { mapping.close(); throw e; }
    }
    ShapeWriter(MemorySegment memory, long mc, long sifu) { this.memory = memory; mapping = null; initialize(mc,sifu); }
    private void initialize(long mc, long sifu) {
        mappingName(mc,sifu);
        if (memory.byteSize() != MAPPING_BYTES) throw new IllegalArgumentException("shape mapping size");
        int magic = (int) INT.getAcquire(memory,0L);
        if (magic != 0) {
            if (magic != MAGIC || memory.get(JAVA_INT,4) != VERSION || memory.get(JAVA_INT,8) != MAPPING_BYTES ||
                memory.get(JAVA_INT,12) != SLOT_BYTES || memory.get(JAVA_LONG,16) != mc || memory.get(JAVA_LONG,24) != sifu ||
                memory.get(JAVA_LONG,32) != LAYOUT_HASH) throw new IllegalArgumentException("incompatible shape mapping");
            return;
        }
        memory.fill((byte)0); memory.set(JAVA_INT,4,VERSION); memory.set(JAVA_INT,8,MAPPING_BYTES);
        memory.set(JAVA_INT,12,SLOT_BYTES); memory.set(JAVA_LONG,16,mc); memory.set(JAVA_LONG,24,sifu);
        memory.set(JAVA_LONG,32,LAYOUT_HASH); INT.setRelease(memory,0L,MAGIC);
    }
    public boolean publish(byte[] payload, int sections, long revision, long now, int world, String map, long saveKey) {
        byte[] name = map.getBytes(StandardCharsets.UTF_8);
        if (payload.length > PAYLOAD_BYTES || sections < 0 || sections > MAX_SECTIONS || revision <= 0 || now <= 0 ||
            world == 0 || saveKey == 0 || name.length == 0 || name.length >= 128 || map.indexOf('\0') >= 0)
            throw new IllegalArgumentException("shape snapshot");
        for (int attempt = 0; attempt < SLOTS; attempt++) {
            long at = HEADER_BYTES + (long)next*SLOT_BYTES; next = (next+1)%SLOTS;
            int state = (int)INT.getAcquire(memory,at);
            if ((state != FREE && state != READY) || !INT.compareAndSet(memory,at,state,WRITING)) continue;
            try {
                memory.asSlice(at+4,FRAME_BYTES-4).fill((byte)0);
                memory.set(JAVA_INT,at+4,payload.length); memory.set(JAVA_LONG,at+8,revision);
                memory.set(JAVA_LONG,at+16,now); memory.set(JAVA_INT,at+24,world); memory.set(JAVA_INT,at+28,sections);
                memory.set(JAVA_LONG,at+32,saveKey); MemorySegment.copy(MemorySegment.ofArray(name),0,memory,at+40,name.length);
                MemorySegment.copy(MemorySegment.ofArray(payload),0,memory,at+FRAME_BYTES,payload.length);
                INT.setRelease(memory,at,READY); return true;
            } catch (RuntimeException e) { INT.setRelease(memory,at,FREE); throw e; }
        }
        return false;
    }
    public boolean ready(int world, String map, long save, long now) {
        return appliedRevision(world,map,save,now) > 0;
    }
    public long appliedRevision(int world, String map, long save, long now) {
        long seq = (long)LONG.getAcquire(memory,40L);
        if ((seq&1) != 0) return 0;
        long revision = memory.get(JAVA_LONG,48), hash = memory.get(JAVA_LONG,64), saved = memory.get(JAVA_LONG,72), time = memory.get(JAVA_LONG,80);
        int generation = memory.get(JAVA_INT,56), ready = memory.get(JAVA_INT,60);
        VarHandle.acquireFence();
        boolean valid = seq == (long)LONG.getAcquire(memory,40L) && ready == 1 && revision > 0 && generation == world &&
                hash == mapHash(map) && save == saved && time > 0 && now >= time && now-time <= 500;
        return valid ? revision : 0;
    }
    @Override public void close() { if (mapping != null) mapping.close(); }
}
