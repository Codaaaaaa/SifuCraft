package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldProtocol.*;
import static java.lang.foreign.ValueLayout.*;
import java.lang.foreign.Arena;

public final class WorldWriterTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        WorldViewTest.run();
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate(MAPPING_BYTES, 64);
            var pixels = arena.allocate(64, 4); pixels.fill((byte)0x55);
            float[] projection = new float[16], rotation = new float[16];
            projection[0] = projection[5] = 1; projection[11] = -1; rotation[0] = rotation[5] = rotation[10] = rotation[15] = 1;
            var camera = new WorldWriter.Camera(123, 456, 789, projection, rotation);
            projection[0] = Float.NaN; // Captured cameras cannot alias mutable renderer matrices.
            try (var writer = new WorldWriter(memory, 1, 2)) {
                check(writer.publish(pixels, 4, 2, 7, 1000, 3, 4, 99, camera, true), "paired frame publish");
                long at = slotOffset(0);
                check(memory.get(JAVA_INT, 0) == MAGIC && memory.get(JAVA_LONG, 48) == LAYOUT_HASH &&
                        memory.get(JAVA_INT, at) == READY && memory.get(JAVA_LONG, at + 24) == 1000 &&
                        memory.get(JAVA_INT, at + 32) == 3 && memory.get(JAVA_FLOAT, at + 48) == 123 &&
                        memory.get(JAVA_FLOAT, at + 64) == 1 && memory.get(JAVA_FLOAT, at + 128 + 60) == 1 &&
                        memory.get(JAVA_LONG, at + 192) == 99 && memory.get(JAVA_INT, at + 256) == 0x55555555 &&
                        memory.get(JAVA_INT, at + 256 + 32) == 0x55555555, "golden native camera/time/color/depth offsets");
                for (int i = 0; i < SLOTS; ++i) memory.set(JAVA_INT, slotOffset(i), READING);
                check(!writer.publish(pixels, 4, 2, 8, 1000, 3, 4, 99, camera, true) && memory.get(JAVA_LONG, at + 16) == 7,
                        "world channel cannot overwrite claimed frames or wait on the consumer");
                try { writer.publish(pixels.asSlice(0, 63), 4, 2, 8, 1000, 3, 4, 99, camera, true); throw new AssertionError("short pair"); }
                catch (IllegalArgumentException expected) { }
                memory.set(JAVA_INT,slotOffset(0),FREE);
                var fog=new WorldWriter.Fog(FOG_WATER,.1f,.2f,.3f,.8f,-8,96,32,64);
                var wet=new WorldWriter.Camera(123,456,789,camera.projection(),camera.viewRotation(),fog);
                check(writer.publish(pixels,4,2,9,1000,3,4,99,wet,true),"water fog shares paired image slot");
                check(memory.get(JAVA_INT,at+200)==FOG_WATER&&memory.get(JAVA_FLOAT,at+204)==.1f&&memory.get(JAVA_FLOAT,at+216)==.8f&&memory.get(JAVA_FLOAT,at+220)==-8&&memory.get(JAVA_FLOAT,at+232)==64,"fog ABI offsets");
                memory.set(JAVA_INT,slotOffset(0),FREE);
                check(writer.publish(pixels,4,2,10,1000,3,4,99,camera,true),"dry frame reuses wet slot");
                for(int i=200;i<256;i++)check(memory.get(JAVA_BYTE,at+i)==0,"dry frame clears all fog and reserved bytes");
                try{new WorldWriter.Fog(FOG_LAVA,1,0,0,1,0,0,1,2);throw new AssertionError("zero fog range");}catch(IllegalArgumentException expected){}
                try{new WorldWriter.Fog(FOG_WATER,Float.NaN,0,0,1,0,1,1,2);throw new AssertionError("nonfinite fog");}catch(IllegalArgumentException expected){}
                try { writer.publish(pixels, 4, 2, 8, 1000, 0, 4, 99, camera, true); throw new AssertionError("missing scope"); }
                catch (IllegalArgumentException expected) { }
            }
            memory.set(JAVA_INT, 4, VERSION + 1);
            try { new WorldWriter(memory, 1, 2); throw new AssertionError("foreign protocol"); }
            catch (IllegalArgumentException expected) { }
        }
        check(mappingName(1, 2).equals("Local\\SifuCraft.World.0000000000000001.0000000000000002"), "world mapping ABI");
        System.out.printf("World writer PASS; schema v%d layout=%016X%n", VERSION, LAYOUT_HASH);
    }
}
