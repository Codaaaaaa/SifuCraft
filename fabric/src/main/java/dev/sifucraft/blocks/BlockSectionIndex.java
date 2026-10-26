package dev.sifucraft.blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Derived section snapshots, rebuilt from authoritative chunks. Never writes blocks or saves.
 * An empty snapshot is an explicit deletion. Revisions include removals and chunk unloads.
 * All methods synchronize publication so render-thread readers never see a half-built section.
 * Revisions belong to this instance; a transport must also bind its save/map/session scope.
 */
public final class BlockSectionIndex {
    public record Snapshot(long section, long revision, Map<Short, BlockState> states) {
        public Snapshot { states = Map.copyOf(states); }
    }

    private static final class Section {
        final Map<Short, BlockState> states = new HashMap<>();
        long revision;
    }

    private final Map<Long, Section> sections = new HashMap<>();
    private final Map<Long, Long> dirty = new HashMap<>();
    private long revision;

    public synchronized void changed(BlockPos pos, BlockState state) {
        long key = SectionPos.of(pos).asLong();
        Section section = sections.get(key);
        short local = SectionPos.sectionRelativePos(pos);
        BlockState old = section == null ? null : section.states.get(local);
        if (state.isAir() ? old == null : state == old) return;
        if (section == null) { section = new Section(); sections.put(key, section); }
        if (state.isAir()) section.states.remove(local); else section.states.put(local, state);
        section.revision = ++revision;
        dirty.put(key, revision);
    }

    /** Complete replacement from a loaded chunk, including an empty authoritative section. */
    public synchronized void replace(long key, Map<Short, BlockState> states) {
        for (var entry : states.entrySet()) {
            if ((entry.getKey() & 0xffff) >= 4096 || entry.getValue().isAir())
                throw new IllegalArgumentException("Section records must be local non-air states");
        }
        Section old = sections.get(key);
        if (old != null && old.states.equals(states)) return;
        var next = new Section(); next.states.putAll(states); next.revision = ++revision;
        sections.put(key, next); dirty.put(key, revision);
    }

    public synchronized void unloadChunk(int x, int z) {
        for (var entry : sections.entrySet()) {
            var pos = SectionPos.of(entry.getKey());
            if (pos.x() != x || pos.z() != z) continue;
            // Keep a revision tombstone until acknowledged. Loading again supplies chunk truth.
            if (!entry.getValue().states.isEmpty()) {
                entry.getValue().states.clear(); entry.getValue().revision = ++revision;
                dirty.put(entry.getKey(), revision);
            }
        }
    }

    public synchronized Snapshot snapshot(long key) {
        Section section = sections.get(key);
        return section == null ? null : new Snapshot(key, section.revision, section.states);
    }

    public synchronized List<Snapshot> dirtySnapshots(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative section budget");
        return dirty.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(limit)
                .map(entry -> snapshot(entry.getKey())).toList();
    }

    /** A late acknowledgement cannot clear a newer edit. */
    public synchronized void acknowledge(long key, long appliedRevision) {
        if (dirty.remove(key, appliedRevision)) {
            Section section = sections.get(key);
            if (section != null && section.states.isEmpty()) sections.remove(key);
        }
    }

    public synchronized long revision() { return revision; }
    /** Full reconnect snapshot, including sections whose last update was already acknowledged. */
    public synchronized List<Snapshot> snapshots() {
        return sections.keySet().stream().sorted().map(this::snapshot).toList();
    }
}
