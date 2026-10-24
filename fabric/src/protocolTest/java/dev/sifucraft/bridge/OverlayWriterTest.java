package dev.sifucraft.bridge;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static dev.sifucraft.bridge.OverlayProtocol.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

final class OverlayWriterTest {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    static void run() {
        try (Arena arena = Arena.ofConfined()) {
            var memory = arena.allocate(MAPPING_BYTES, 64);
            var pixels = arena.allocate(32, 8).fill((byte) 0x7F);
            try (var writer = new OverlayWriter(memory, 1, 2)) {
                check(memory.get(JAVA_INT, 0) == MAGIC && memory.get(JAVA_LONG, 48) == LAYOUT_HASH, "overlay header ABI");
                check(writer.publish(pixels, 4, 2, 7, 3, 4, true), "first image publication");
                memory.set(JAVA_LONG, 56, (1280L << 32) | 720);
                check(writer.viewport() == ((1280L << 32) | 720), "atomic native viewport feedback ABI");
                long at = slotOffset(0);
                check(memory.get(JAVA_INT, at) == READY && memory.get(JAVA_INT, at + 12) == 16 &&
                        memory.get(JAVA_LONG, at + 16) == 7 && memory.get(JAVA_INT, at + 24) == 3 &&
                        memory.get(JAVA_INT, at + 28) == 4 && memory.get(JAVA_INT, at + 36) == 32 &&
                        memory.get(JAVA_INT, at + 64) == 0x7F7F7F7F, "golden native frame offsets and payload");
                // Simulate a reader holding every slot: the writer must return, preserving all bytes.
                for (int i = 0; i < SLOTS; ++i) memory.set(JAVA_INT, slotOffset(i), READING);
                check(!writer.publish(pixels, 4, 2, 8, 3, 4, false), "never overwrite a reading slot or wait for a consumer");
                check(memory.get(JAVA_LONG, at + 16) == 7, "held reader data stays intact");
                memory.set(JAVA_INT, slotOffset(1), READY);
                check(writer.publish(pixels, 4, 2, 9, 3, 4, false, 1, 0, 3, 2, 2) &&
                        memory.get(JAVA_LONG, slotOffset(1) + 16) == 9 &&
                        memory.get(JAVA_INT, slotOffset(1) + 40) == 1 &&
                        memory.get(JAVA_INT, slotOffset(1) + 48) == 3 &&
                        memory.get(JAVA_INT, slotOffset(1) + 56) == 2, "replace unread frame and publish inverse GUI bounds");
                try { writer.publish(pixels, 0, 2, 10, 3, 4, false); throw new AssertionError("zero width"); }
                catch (IllegalArgumentException expected) { }
                try { writer.publish(pixels.asSlice(0, 31), 4, 2, 10, 3, 4, false); throw new AssertionError("short image"); }
                catch (IllegalArgumentException expected) { }
                try { writer.publish(pixels, 4, 2, 10, 0, 4, false); throw new AssertionError("missing scope"); }
                catch (IllegalArgumentException expected) { }
            }
            memory.set(JAVA_INT, 4, VERSION + 1);
            try { new OverlayWriter(memory, 1, 2); throw new AssertionError("incompatible existing mapping"); }
            catch (IllegalArgumentException expected) { }
        }
        check(mappingName(1, 2).equals("Local\\SifuCraft.Overlay.0000000000000001.0000000000000002"), "session mapping ABI");
        System.out.printf("Overlay writer PASS; schema v%d layout=%016X%n", VERSION, LAYOUT_HASH);
    }
}
