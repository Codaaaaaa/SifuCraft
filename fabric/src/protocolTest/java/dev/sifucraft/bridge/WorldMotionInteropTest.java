package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Separate named mapping and fake peer. Does not connect to MainBridge or a live game. */
public final class WorldMotionInteropTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static Command command(long id){
        return new Command(RESERVE,MACHINE,id,0,1000,21,22,23,24,31,32,5,7,42,2,500,
            new SifuFrame.Vec3(10,20,90),SifuFrame.Vec3.ZERO,60,35,92,0,-1,64,5,0,.5f,0,41,6,0,0);
    }
    public static void main(String[] args) throws Exception {
        check(args.length==1,"Native protocol test binary required");var exe=Path.of(args[0]).toAbsolutePath().normalize();
        check(Files.isRegularFile(exe)&&exe.getFileName().toString().equals("sifucraft_world_motion_tests.exe"),"expected dedicated Native test helper");
        long mc=System.nanoTime()^(ProcessHandle.current().pid()<<32),nativeSession=mc^0x51fa4400L;check(mc!=0&&nativeSession!=0,"unique sessions");
        var report=Path.of("build/reports/world-motion");Files.createDirectories(report);Process child=null;
        try(var mailbox=new WorldMotionMailbox(mc,nativeSession)){
            int sent=0,received=0;
            while(sent<SLOTS){check(mailbox.publish(command(sent+1),WorldWriter.clockMillis()),"initial reliable ring fill");sent++;}
            check(!mailbox.publish(command(sent+1),WorldWriter.clockMillis()),"real OS ring backpressure before consumer");
            child=new ProcessBuilder(exe.toString(),"--echo",Long.toUnsignedString(mc),Long.toUnsignedString(nativeSession),"65")
                .redirectErrorStream(true).redirectOutput(report.resolve("native-echo.log").toFile()).start();
            Thread.sleep(100); // let the reverse ring fill before any receipt is consumed
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean statusSeen=false;
            while(received<64&&System.nanoTime()<deadline){
                var status=mailbox.status(WorldWriter.clockMillis());if(status!=null){
                    check(status.world()==7&&status.playerStamp()==((73L<<32)|19)&&(status.capabilities()&PLAYER_MACHINE_CAPABILITY)!=0,"native scoped player stamp/capability");
                    check(status.player()!=null&&status.player().position().equals(new SifuFrame.Vec3(101,-202,303))&&status.player().radius()==35&&status.player().halfHeight()==90&&status.player().health()==180,"C++ publishes exact player metadata read independently by Java");statusSeen=true;
                }
                if(sent<64&&mailbox.publish(command(sent+1),WorldWriter.clockMillis()))sent++;
                var reply=mailbox.poll();
                if(reply!=null){
                    received++;check(reply.id()==received&&reply.status()==11&&reply.target()==42&&reply.spawn()==2,"ordered reliable C++ receipt");
                    check(reply.sourceHash()==hash(encode(command(received)),200),"native receipt matches exact submitted payload");
                }else Thread.sleep(1);
            }
            check(sent==64&&received==64&&statusSeen,"all cross-process commands and receipts retained");
            check(mailbox.publish(command(1),WorldWriter.clockMillis()),"retransmit completed request with same identity");
            Reply duplicate=null;long retryEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(duplicate==null&&System.nanoTime()<retryEnd){duplicate=mailbox.poll();if(duplicate==null)Thread.sleep(1);}
            check(duplicate!=null&&duplicate.id()==1&&duplicate.sourceHash()==hash(encode(command(1)),200),"retransmit receives cached completion");
            check(child.waitFor(5,TimeUnit.SECONDS)&&child.exitValue()==0,"Native helper normal exit");
            check(Files.readString(report.resolve("native-echo.log")).contains("packets=65 unique_handlers=64"),"one retransmit never repeats a handler");
            System.out.println("World motion interop PASS: 64 commands/receipts + exact cached retry, real OS mapping, both-ring backpressure, no game access");
        }finally{if(child!=null&&child.isAlive())child.destroyForcibly();}
    }
}
