package dev.sifucraft.bridge;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/** Head bone poses of nearby enemies, keyed like {@link EnemyFrame}. Unreal space; forward/up are unit. */
public record EnemyHeads(Map<EnemyFrame.Key, Head> heads) {
    public static final EnemyHeads NONE = new EnemyHeads(Map.of());

    public EnemyHeads { heads = Map.copyOf(heads); }

    public record Head(SifuFrame.Vec3 location, SifuFrame.Vec3 forward, SifuFrame.Vec3 up) {}

    public Head get(EnemyFrame.Key key) { return heads.get(key); }

    /** Decode an already copied table; malformed entries are dropped, not the whole table. */
    public static EnemyHeads decode(MemorySegment table) {
        int count = table.get(JAVA_INT_UNALIGNED, Protocol.EnemyHeads.COUNT);
        if (count < 0 || count > Protocol.MAX_ENEMIES) throw new IllegalArgumentException("head count " + count);
        var heads = new HashMap<EnemyFrame.Key, Head>();
        for (int i = 0; i < count; i++) {
            long at = Protocol.EnemyHeads.ENTRIES + (long) i * Protocol.EnemyHeads.ENTRY_SIZE;
            var key = new EnemyFrame.Key(table.get(JAVA_INT_UNALIGNED, at + Protocol.EnemyHeads.ID),
                    table.get(JAVA_INT_UNALIGNED, at + Protocol.EnemyHeads.SPAWN));
            var head = new Head(vec(table, at + Protocol.EnemyHeads.LOCATION), vec(table, at + Protocol.EnemyHeads.FORWARD),
                    vec(table, at + Protocol.EnemyHeads.UP));
            if (key.id() >= 0 && key.spawn() != 0 && finite(head.location()) && unit(head.forward()) && unit(head.up()))
                heads.put(key, head);
        }
        return new EnemyHeads(heads);
    }

    private static SifuFrame.Vec3 vec(MemorySegment data, long at) {
        return new SifuFrame.Vec3(data.get(JAVA_FLOAT_UNALIGNED, at), data.get(JAVA_FLOAT_UNALIGNED, at + 4),
                data.get(JAVA_FLOAT_UNALIGNED, at + 8));
    }

    private static boolean finite(SifuFrame.Vec3 v) {
        return Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z());
    }

    private static boolean unit(SifuFrame.Vec3 v) {
        return finite(v) && Math.abs(v.x() * v.x() + v.y() * v.y() + v.z() * v.z() - 1) < 0.01f;
    }
}
