package dev.sifucraft.bridge;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** An immutable, validated copy of Sifu's nearby-enemy table, safe to hand to the server thread. */
public record EnemyFrame(long session, long frame, int worldGeneration, long receivedMs, List<Entry> entries) {
    public EnemyFrame {
        entries = List.copyOf(entries);
    }

    public record Key(int id, int spawn) {}

    public record Entry(int id, int spawn, SifuFrame.Vec3 location, float yaw, float radius, float halfHeight,
                        float health, float maxHealth, int flags, int faction, SifuFrame.Vec3 velocity) {
        public Key key() { return new Key(id, spawn); }
        public boolean alive() { return (flags & Protocol.ENEMY_ALIVE) != 0 && health > 0; }
    }

    /** Decode an already copied table. The caller must validate the seqlock around that copy. */
    public static EnemyFrame decode(long session, MemorySegment table, long receivedMs) {
        int count = table.get(JAVA_INT_UNALIGNED, Protocol.Enemies.COUNT);
        if (count < 0 || count > Protocol.MAX_ENEMIES) throw new IllegalArgumentException("enemy count " + count);
        var entries = new ArrayList<Entry>(count);
        var ids = new HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            long at = Protocol.Enemies.ENTRIES + (long) i * Protocol.Enemies.ENTRY_SIZE;
            int id = table.get(JAVA_INT_UNALIGNED, at + Protocol.Enemies.ID);
            int spawn = table.get(JAVA_INT_UNALIGNED, at + Protocol.Enemies.SPAWN);
            SifuFrame.Vec3 location = vec(table, at + Protocol.Enemies.LOCATION);
            SifuFrame.Vec3 velocity = vec(table, at + Protocol.Enemies.VELOCITY);
            float yaw = table.get(JAVA_FLOAT_UNALIGNED, at + Protocol.Enemies.YAW);
            float radius = table.get(JAVA_FLOAT_UNALIGNED, at + Protocol.Enemies.RADIUS);
            float half = table.get(JAVA_FLOAT_UNALIGNED, at + Protocol.Enemies.HALF_HEIGHT);
            float health = table.get(JAVA_FLOAT_UNALIGNED, at + Protocol.Enemies.HEALTH);
            float max = table.get(JAVA_FLOAT_UNALIGNED, at + Protocol.Enemies.MAX_HEALTH);
            if (id < 0 || spawn == 0 || !ids.add(id) || !finite(location) || !finite(velocity) ||
                    !Float.isFinite(yaw) || !Float.isFinite(radius) || !Float.isFinite(half) ||
                    radius <= 0 || radius > 1000 || half < radius || half > 2000 ||
                    !Float.isFinite(health) || !Float.isFinite(max) || health < 0 || max <= 0) {
                throw new IllegalArgumentException("invalid enemy entry " + i);
            }
            entries.add(new Entry(id, spawn, location, yaw, radius, half, health, max,
                    table.get(JAVA_INT_UNALIGNED, at + Protocol.Enemies.FLAGS),
                    table.get(JAVA_INT_UNALIGNED, at + Protocol.Enemies.FACTION), velocity));
        }
        return new EnemyFrame(session, table.get(JAVA_LONG_UNALIGNED, Protocol.Enemies.FRAME),
                table.get(JAVA_INT_UNALIGNED, Protocol.Enemies.WORLD_GENERATION), receivedMs, entries);
    }

    private static SifuFrame.Vec3 vec(MemorySegment data, long at) {
        return new SifuFrame.Vec3(data.get(JAVA_FLOAT_UNALIGNED, at), data.get(JAVA_FLOAT_UNALIGNED, at + 4),
                data.get(JAVA_FLOAT_UNALIGNED, at + 8));
    }

    private static boolean finite(SifuFrame.Vec3 v) {
        return Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z());
    }
}
