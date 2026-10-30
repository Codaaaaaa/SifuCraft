package dev.sifucraft.bridge;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Golden v7 wire bytes and malformed producer data; no running game is needed. */
public final class EnemyFrameTest {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static byte[] table() {
        var b = ByteBuffer.allocate(0x840).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(8, 17).putInt(16, 3).putInt(20, 1);
        b.putInt(64, 42).putInt(68, 9);
        b.putFloat(72, 123).putFloat(76, -456).putFloat(80, 789).putFloat(84, 90);
        b.putFloat(88, 35).putFloat(92, 92).putFloat(96, 60).putFloat(100, 100);
        b.putInt(104, 9).putInt(108, 1);
        b.putFloat(112, 10).putFloat(116, 20).putFloat(120, 30);
        return b.array();
    }
    private static void rejects(byte[] data, String label) {
        try {
            EnemyFrame.decode(1, MemorySegment.ofArray(data), 2);
            throw new AssertionError("accepted " + label);
        } catch (IllegalArgumentException expected) {
            // An unrelated exception still fails the test.
        }
    }
    public static void main(String[] args) {
        NativeInteractionTest.run();
        LanguageMatchTest.run();
        ShieldWireTest.run();
        ImpulseWireTest.run();
        OverlayWriterTest.run();
        GuiCursorTest.run();
        RespawnHandshakeTest.run();
        byte[] bytes = table();
        EnemyFrame frame = EnemyFrame.decode(1234, MemorySegment.ofArray(bytes), 5678);
        EnemyFrame.Entry e = frame.entries().getFirst();
        check(frame.session() == 1234 && frame.frame() == 17 && frame.worldGeneration() == 3 && frame.receivedMs() == 5678,
                "table/session metadata");
        check(e.id() == 42 && e.spawn() == 9 && e.location().x() == 123 && e.location().y() == -456 &&
                e.location().z() == 789 && e.yaw() == 90 && e.radius() == 35 && e.halfHeight() == 92 &&
                e.health() == 60 && e.maxHealth() == 100 && e.faction() == 1 && e.alive() && e.velocity().z() == 30,
                "golden C++ enemy entry layout");
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(64, 7);
        check(e.id() == 42, "decoded snapshot survives later producer writes");
        bytes = table();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 33);
        rejects(bytes, "oversized table");
        bytes = table();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(20, -1);
        rejects(bytes, "negative count");
        bytes = table();
        System.arraycopy(bytes, 64, bytes, 128, 64);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 2).putInt(132, 10);
        rejects(bytes, "two activations of one UObject");
        for (int offset : new int[] {72, 76, 80, 84, 88, 92, 96, 100, 112, 116, 120}) {
            bytes = table();
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, Float.NaN);
            rejects(bytes, "non-finite field at " + offset);
        }
        bytes = table();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(68, 0);
        rejects(bytes, "uninitialized activation");
        bytes = table();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(92, 10);
        rejects(bytes, "capsule shorter than its radius");
        bytes = table();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(96, 0);
        check(!EnemyFrame.decode(1, MemorySegment.ofArray(bytes), 2).entries().getFirst().alive(), "zero HP is dead");
        bytes = table();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0);
        check(EnemyFrame.decode(1, MemorySegment.ofArray(bytes), 2).entries().isEmpty(), "empty table clears enemies");
        var hitBytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        hitBytes.putInt(101).putInt(2).putFloat(18).putInt(0x107).putInt(3).putInt(4).putFloat(-1).putFloat(0);
        var hit = PlayerHit.decode(hitBytes.array());
        check(hit.attackerId() == 101 && hit.spawn() == 2 && hit.sifuDamage() == 18 && hit.attackPower() == 1 &&
                hit.worldGeneration() == 3 && hit.anchorEpoch() == 4 && hit.dirX() == -1, "golden C++ PlayerHit layout");
        for (int offset : new int[] {8, 24, 28}) {
            byte[] badHit = hitBytes.array().clone();
            ByteBuffer.wrap(badHit).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, Float.NaN);
            try { PlayerHit.decode(badHit); throw new AssertionError("accepted non-finite player hit"); }
            catch (IllegalArgumentException expected) { }
        }
        for (int offset : new int[] {16, 20}) {
            byte[] badHit = hitBytes.array().clone();
            ByteBuffer.wrap(badHit).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, 0);
            try { PlayerHit.decode(badHit); throw new AssertionError("accepted unscoped player hit"); }
            catch (IllegalArgumentException expected) { }
        }
        var heads = ByteBuffer.allocate(Protocol.EnemyHeads.SIZE).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 2);
        heads.putInt(64, 42).putInt(68, 9).putFloat(72, 1).putFloat(76, 2).putFloat(80, 3)
                .putFloat(84, 1).putFloat(104, 1); // forward +X, up +Z
        heads.putInt(112, 43).putInt(116, 9).putFloat(132, 2).putFloat(152, 1); // forward not unit
        var head = EnemyHeads.decode(MemorySegment.ofArray(heads.array()));
        check(head.heads().size() == 1 && head.get(new EnemyFrame.Key(42, 9)).location().z() == 3 &&
                head.get(new EnemyFrame.Key(42, 9)).up().z() == 1, "golden C++ EnemyHeadEntry layout, malformed entry dropped");
        System.out.printf("EnemyFrame/PlayerHit tests PASS; protocol v%d layout=%016X%n", Protocol.VERSION, Protocol.LAYOUT_HASH);
    }
}
