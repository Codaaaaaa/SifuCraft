package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class WorldMotionProtocolTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("accepted invalid motion packet");}catch(IllegalArgumentException expected){}}
    private static Command sample(int op,int source,SifuFrame.Vec3 delta,int duration,int flags,int target,int spawn){
        return new Command(op,source,11,op==STEP||op==RELEASE||op==CANCEL?71:0,1000,21,22,23,24,31,32,5,7,target,spawn,duration,
            new SifuFrame.Vec3(10,20,90),delta,60,35,92,flags,-1,64,5,0,.5f,op==DAMAGE?3:0,41,6,flags==PLAYER?73:0,op==STEP?72:0);
    }
    private static byte[] fixture(String name) throws Exception {return HexFormat.of().parseHex(Files.readString(Path.of("../protocol/fixtures/"+name)).replaceAll("\\s+",""));}
    public static void main(String[] args) throws Exception {
        var reserve=sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,500,0,42,2);
        check(Arrays.equals(encode(reserve),fixture("world_motion_command.hex")),"independent C++/Java command golden");
        var reply=decodeReply(fixture("world_motion_reply.hex"));
        check(reply.id()==11&&reply.lease()==12&&reply.actorSerial()==99&&reply.restored()==7&&reply.componentToken()==55&&reply.position().z()==90&&reply.delta().x()==10&&reply.rootRadius()==35&&reply.rootHalfHeight()==90,
            "C++ receipt fields and restored mask");
        invalid(()->encode(sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,501,0,42,2)));
        invalid(()->encode(sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,500,0,42,0)));
        invalid(()->encode(sample(STEP,MACHINE,new SifuFrame.Vec3(6,0,6),100,0,42,2)));
        invalid(()->encode(sample(STEP,FLUID,new SifuFrame.Vec3(6,6,6),100,0,42,2)));
        invalid(()->encode(sample(STEP,FLUID,new SifuFrame.Vec3(Float.NaN,0,0),100,0,42,2)));
        check(encode(sample(STEP,MACHINE,new SifuFrame.Vec3(0,0,10),100,0,42,2)).length==COMMAND_BYTES,"vertical step has bounded complete payload");
        check(encode(sample(STEP,FLUID,new SifuFrame.Vec3(6,0,6),100,0,42,2)).length==COMMAND_BYTES,"fluid vector length rather than per-axis clamp");
        check(encode(sample(DAMAGE,WORLD_EFFECT,SifuFrame.Vec3.ZERO,0,0,42,2)).length==COMMAND_BYTES,"world source damage");
        check(encode(sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,100,PLAYER,0,0)).length==COMMAND_BYTES,"player lifetime scope");
        var bytes=fixture("world_motion_reply.hex");bytes[72]^=1;invalid(()->decodeReply(bytes));
        invalid(()->decodeReply(new byte[REPLY_BYTES-1]));
        check(!mappingName(11,22).equals(mappingName(12,22)),"mapping changes with MC session");
        invalid(()->mappingName(0,22));
        try(var arena=Arena.ofConfined()){
            var memory=arena.allocate(MAPPING_BYTES,64);var mailbox=new WorldMotionMailbox(memory,11,22);
            for(int i=0;i<SLOTS;i++)check(mailbox.publish(reserve,1000),"fill every reliable command slot");
            check(!mailbox.publish(reserve,1000)&&memory.get(JAVA_LONG,HEADER_BYTES+16+8)==11,"full command ring never overwrites ready packet");
            memory.set(JAVA_INT,HEADER_BYTES,READING);check(!mailbox.publish(reserve,1000),"claimed slot cannot be overwritten");
            memory.set(JAVA_INT,HEADER_BYTES,FREE);check(mailbox.publish(reserve,1000),"returned command capacity can be reused");
            check(mailbox.status(1000)==null,"no ready before Native status");
            memory.set(JAVA_LONG,40,3);memory.set(JAVA_LONG,48,1000);memory.set(JAVA_INT,56,7);memory.set(JAVA_LONG,80,2);memory.set(JAVA_LONG,88,73);
            check(mailbox.status(1200).playerStamp()==73&&mailbox.status(1600)==null,"fresh native world and player lifetime");
            memory.set(JAVA_INT,60,PAUSED);check(mailbox.status(1200).flags()==PAUSED,"true pause has a world flag separate from input ownership");
            memory.set(JAVA_INT,60,4);check(mailbox.status(1200)==null,"unknown world flags fail closed");memory.set(JAVA_INT,60,0);
            memory.set(JAVA_LONG,80,3);check(mailbox.status(1200)==null,"torn native status rejected");memory.set(JAVA_LONG,80,4);
            long at=HEADER_BYTES+(long)SLOTS*SLOT_BYTES;
            byte[] replyBytes=fixture("world_motion_reply.hex");memory.set(JAVA_INT,at,READY);memory.set(JAVA_LONG,at+8,1);
            MemorySegment.copy(MemorySegment.ofArray(replyBytes),0,memory,at+16,REPLY_BYTES);
            check(mailbox.poll().restored()==7&&memory.get(JAVA_INT,at)==FREE,"receipt copied and released");
            memory.set(JAVA_INT,at,READY);memory.set(JAVA_LONG,at+16+120,0);
            invalid(mailbox::poll);check(memory.get(JAVA_INT,at)==FREE,"bad receipt does not poison the ring");
            invalid(()->new WorldMotionMailbox(memory,12,22));
        }
        System.out.printf("World motion protocol PASS layout=%016X golden/malformed/3D/player scope%n",LAYOUT_HASH);
    }
}
