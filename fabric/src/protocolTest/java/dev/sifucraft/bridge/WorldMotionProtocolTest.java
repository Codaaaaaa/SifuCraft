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
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

public final class WorldMotionProtocolTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("accepted invalid motion packet");}catch(IllegalArgumentException expected){}}
    private static Command sample(int op,int source,SifuFrame.Vec3 delta,int duration,int flags,int target,int spawn){
        return new Command(op,source,11,op==STEP||op==RELEASE||op==CANCEL||op==RENEW?71:0,1000,21,22,23,24,31,32,5,7,target,spawn,duration,
            new SifuFrame.Vec3(10,20,90),delta,60,35,92,flags,-1,64,5,0,.5f,op==DAMAGE?3:0,41,6,flags==PLAYER?73:0,op==STEP||op==RENEW?72:0);
    }
    private static byte[] fixture(String name) throws Exception {return HexFormat.of().parseHex(Files.readString(Path.of("../protocol/fixtures/"+name)).replaceAll("\\s+",""));}
    public static void main(String[] args) throws Exception {
        var query=new Command(COVERAGE,WORLD_EFFECT,1,0,1000,1,0,1,0,31,32,0,7,0,0,0,new SifuFrame.Vec3(-16800,0,0),SifuFrame.Vec3.ZERO,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
        check(encode(query).length==COMMAND_BYTES,"finite coverage has no actor or movement lease");
        var reserve=sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,500,0,42,2);
        check(Arrays.equals(encode(reserve),fixture("world_motion_command.hex")),"independent C++/Java command golden");
        var reply=decodeReply(fixture("world_motion_reply.hex"));
        check(reply.id()==11&&reply.lease()==12&&reply.actorSerial()==99&&reply.restored()==7&&reply.componentToken()==55&&reply.position().z()==90&&reply.delta().x()==10&&reply.rootRadius()==35&&reply.rootHalfHeight()==90,
            "C++ receipt fields and restored mask");
        var pose=fixture("world_motion_reply.hex");var poseBytes=java.nio.ByteBuffer.wrap(pose).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        poseBytes.putInt(36,0).putInt(40,0).putInt(44,3).putInt(52,1).putInt(100,PLAYER_POSE_HANDOFF);
        for(int offset:new int[]{84,88,92})poseBytes.putFloat(offset,0);poseBytes.putLong(120,hash(pose,120));
        check(decodeReply(pose).flags()==PLAYER_POSE_HANDOFF,"MC pose-owner restoration is distinct from an original tick");
        for(int offset:new int[]{36,40,44,48,52,56,84}){
            var bad=pose.clone();var bytes=java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(offset==56)bytes.putLong(offset,0);else if(offset==84)bytes.putFloat(offset,1);else bytes.putInt(offset,switch(offset){case 44->2;case 48->0;case 52->7;default->1;});
            bytes.putLong(120,hash(bad,120));invalid(()->decodeReply(bad));
        }
        invalid(()->encode(sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,501,0,42,2)));
        invalid(()->encode(sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,500,0,42,0)));
        invalid(()->encode(sample(STEP,MACHINE,new SifuFrame.Vec3(6,0,6),100,0,42,2)));
        invalid(()->encode(sample(STEP,FLUID,new SifuFrame.Vec3(6,6,6),100,0,42,2)));
        invalid(()->encode(sample(STEP,FLUID,new SifuFrame.Vec3(Float.NaN,0,0),100,0,42,2)));
        check(encode(sample(STEP,MACHINE,new SifuFrame.Vec3(0,0,10),100,0,42,2)).length==COMMAND_BYTES,"vertical step has bounded complete payload");
        check(encode(sample(STEP,FLUID,new SifuFrame.Vec3(6,0,6),100,0,42,2)).length==COMMAND_BYTES,"fluid vector length rather than per-axis clamp");
        check(encode(sample(DAMAGE,WORLD_EFFECT,SifuFrame.Vec3.ZERO,0,0,42,2)).length==COMMAND_BYTES,"world source damage");
        check(encode(sample(RENEW,FLUID,SifuFrame.Vec3.ZERO,500,0,42,2)).length==COMMAND_BYTES,"bounded fresh fluid heartbeat");
        check(encode(sample(RENEW,EXPLOSION,SifuFrame.Vec3.ZERO,500,0,42,2)).length==COMMAND_BYTES,"bounded independent explosion heartbeat");
        check(encode(sample(STEP,EXPLOSION,new SifuFrame.Vec3(6,0,6),0,0,42,2)).length==COMMAND_BYTES,"explosion keeps bounded original 3D direction");
        var poseCommand=sample(STEP,PASSENGER,SifuFrame.Vec3.ZERO,0,0,42,2);
        var facingCommand=new Command(poseCommand.op(),poseCommand.source(),poseCommand.id(),poseCommand.lease(),poseCommand.issuedMs(),poseCommand.sourceHi(),poseCommand.sourceLo(),poseCommand.action(),poseCommand.tick(),poseCommand.save(),poseCommand.map(),poseCommand.shapeRevision(),poseCommand.world(),poseCommand.target(),poseCommand.spawn(),poseCommand.durationMs(),poseCommand.position(),poseCommand.delta(),poseCommand.health(),poseCommand.radius(),poseCommand.halfHeight(),poseCommand.flags(),poseCommand.blockX(),poseCommand.blockY(),poseCommand.blockZ(),poseCommand.progress(),poseCommand.nextProgress(),poseCommand.amount(),poseCommand.stateHash(),poseCommand.sectionRevision(),poseCommand.actorStamp(),poseCommand.componentToken(),-175.5f);
        check(Arrays.equals(encode(facingCommand),fixture("world_motion_passenger_command.hex")),"independent C++/Java signed passenger heading golden");
        check(encode(poseCommand).length==COMMAND_BYTES,"passenger permits a bounded rotation without invented displacement");
        var corruptFacing=encode(poseCommand);var facingBytes=java.nio.ByteBuffer.wrap(corruptFacing).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        check(facingBytes.getFloat(200)==0&&facingBytes.getInt(204)==0&&facingBytes.getLong(208)==hash(corruptFacing,208),"explicit passenger facing, reserved field and full command digest offsets");
        invalid(()->encode(sample(RENEW,MACHINE,SifuFrame.Vec3.ZERO,500,0,42,2)));
        invalid(()->encode(sample(RENEW,FLUID,SifuFrame.Vec3.ZERO,0,0,42,2)));
        invalid(()->encode(sample(RENEW,FLUID,new SifuFrame.Vec3(1,0,0),500,0,42,2)));
        check(encode(sample(RESERVE,MACHINE,SifuFrame.Vec3.ZERO,100,PLAYER,0,0)).length==COMMAND_BYTES,"player lifetime scope");
        var bytes=fixture("world_motion_reply.hex");bytes[72]^=1;invalid(()->decodeReply(bytes));
        invalid(()->decodeReply(new byte[REPLY_BYTES-1]));
        var credit=fixture("world_motion_reply.hex");var packet=java.nio.ByteBuffer.wrap(credit).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        packet.putInt(44,9);packet.putInt(100,STEP_NOT_EXECUTED);
        for(int offset=84;offset<96;offset+=4)packet.putFloat(offset,0);
        packet.putLong(120,hash(credit,120));check(decodeReply(credit).flags()==STEP_NOT_EXECUTED,"fresh rejection proof");
        for(int mutation=0;mutation<3;mutation++){
            var bad=credit.clone();var b=java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(mutation==0)b.putInt(44,2);
            if(mutation==1)b.putFloat(84,.01f);
            if(mutation==2)b.putInt(100,STEP_NOT_EXECUTED|1);
            b.putLong(120,hash(bad,120));invalid(()->decodeReply(bad));
        }
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
            memory.set(JAVA_LONG,40,MOTION_CAPABILITY|PLAYER_MACHINE_CAPABILITY);memory.set(JAVA_LONG,88,(73L<<32)|19);
            for(int i=0;i<6;i++)memory.set(JAVA_FLOAT,104+i*4,new float[]{101,-202,303,35,90,180}[i]);
            var playerStatus=mailbox.status(1200);check(playerStatus!=null&&playerStatus.player().position().equals(new SifuFrame.Vec3(101,-202,303))&&playerStatus.player().radius()==35&&playerStatus.player().halfHeight()==90&&playerStatus.player().health()==180,"atomic original player root/geometry/HP metadata");
            memory.set(JAVA_LONG,40,PLAYER_FLUID_CAPABILITY);check(mailbox.status(1200).player().radius()==35,"player fluid permission carries the same typed capsule without machine permission");
            for(int offset:new int[]{104,108,112,116,120,124}){float original=memory.get(JAVA_FLOAT,offset);memory.set(JAVA_FLOAT,offset,Float.NaN);check(mailbox.status(1200)==null,"nonfinite player metadata fails closed");memory.set(JAVA_FLOAT,offset,original);}
            memory.set(JAVA_FLOAT,120,34);check(mailbox.status(1200)==null,"invalid player capsule ratio");memory.set(JAVA_FLOAT,120,90);
            memory.set(JAVA_FLOAT,124,0);check(mailbox.status(1200)==null,"dead player cannot advertise ready mechanics");memory.set(JAVA_FLOAT,124,180);
            memory.set(JAVA_LONG,88,19);check(mailbox.status(1200)==null,"player index is part of ready metadata");memory.set(JAVA_LONG,88,(73L<<32)|19);
            memory.set(JAVA_LONG,88,(-1L<<32)|19);check(mailbox.status(1200)==null,"negative native player object index fails closed");memory.set(JAVA_LONG,88,(73L<<32)|19);
            memory.set(JAVA_LONG,80,3);check(mailbox.status(1200)==null,"player metadata cannot escape the shared status seqlock");memory.set(JAVA_LONG,80,4);
            memory.set(JAVA_INT,4,5);check(mailbox.status(1200)==null&&!mailbox.publish(reserve,1200),"old world-motion version cannot exchange commands/status");memory.set(JAVA_INT,4,VERSION);
            memory.set(JAVA_LONG,40,3);memory.set(JAVA_LONG,88,73);
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
