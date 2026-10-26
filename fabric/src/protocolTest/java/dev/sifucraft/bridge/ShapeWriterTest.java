package dev.sifucraft.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import static dev.sifucraft.bridge.ShapeProtocol.*;

public final class ShapeWriterTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        try (var arena = Arena.ofConfined()) {
            MemorySegment memory = arena.allocate(MAPPING_BYTES,8);
            var writer = new ShapeWriter(memory,11,22);
            byte[] box = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(25).putFloat(0)
                    .putFloat(100).putFloat(75).putFloat(50).array();
            byte[] payload = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).putInt(-16).putInt(32).putInt(0)
                    .putInt(1).putLong(7).putLong(hash(box)).put(box).array();
            check(writer.publish(payload,1,3,1000,9,"fixture",77),"publish full shape snapshot");
            long first = HEADER_BYTES;
            check(memory.get(JAVA_INT,first) == READY && memory.get(JAVA_LONG,first+8) == 3 && memory.get(JAVA_INT,first+28) == 1,"golden offsets");
            memory.set(JAVA_INT,first,READING);
            check(writer.publish(new byte[0],0,4,1001,9,"fixture",77),"empty deletion snapshot");
            memory.set(JAVA_INT,HEADER_BYTES+(long)SLOT_BYTES,READING);
            check(!writer.publish(payload,1,5,1002,9,"fixture",77),"claimed slots cause backpressure");
            check(memory.get(JAVA_LONG,first+8) == 3,"claimed snapshot unchanged");
            check(!writer.ready(9,"fixture",77,1000),"no fake ready before native ACK");
            memory.set(JAVA_LONG,40,2); memory.set(JAVA_LONG,48,3); memory.set(JAVA_INT,56,9); memory.set(JAVA_INT,60,1);
            memory.set(JAVA_LONG,64,mapHash("fixture")); memory.set(JAVA_LONG,72,77); memory.set(JAVA_LONG,80,1000);
            check(writer.ready(9,"fixture",77,1200),"scoped acknowledgement");
            check(writer.appliedRevision(9,"fixture",77,1200)==3,"presentation readiness does not acknowledge the newer published revision 4");
            check(!writer.ready(10,"fixture",77,1200) && !writer.ready(9,"other",77,1200) && !writer.ready(9,"fixture",78,1200),"old world/map/save ACK rejected");
            check(!writer.ready(9,"fixture",77,1600),"stale native presentation rejected");
            memory.set(JAVA_LONG,40,3); check(!writer.ready(9,"fixture",77,1200),"torn acknowledgement rejected");
            var golden = ByteBuffer.allocate(FRAME_BYTES+payload.length).order(ByteOrder.LITTLE_ENDIAN);
            golden.putInt(READY).putInt(payload.length).putLong(3).putLong(1000).putInt(9).putInt(1).putLong(77);
            golden.put("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)); golden.position(FRAME_BYTES); golden.put(payload);
            Path output = Path.of("build/reports/p8/shapes-golden.bin"); Files.createDirectories(output.getParent()); Files.write(output,golden.array());
        }
        System.out.printf("Shape protocol PASS layout=%016X: ownership/backpressure/deletion/scoped ACK/golden bytes%n",LAYOUT_HASH);
    }
}
