package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class WorldMotionRequestsTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final class Fake implements WorldMotionRequests.Transport {
        final ArrayList<Command> commands=new ArrayList<>();final ArrayDeque<Reply> replies=new ArrayDeque<>();
        boolean room=true,closed,fail,missingStatus;int flags;
        public boolean publish(Command c,long now){if(!room)return false;commands.add(c);if(fail)throw new IllegalStateException("after publish");return true;}
        public Reply poll(){return replies.poll();}
        public WorldMotionMailbox.NativeStatus status(long now){return missingStatus?null:new WorldMotionMailbox.NativeStatus(1,now,7,flags,19,5);}
        public void close(){closed=true;}
    }
    private static Command command(long id,int op,long now){return new Command(op,MACHINE,id,op==RESERVE?0:11,now,21,22,23,24,31,32,5,7,42,2,500,
        new SifuFrame.Vec3(10,20,90),new SifuFrame.Vec3(op==STEP?10:0,0,0),60,35,92,0,-1,64,5,0,.5f,op==DAMAGE?2:0,41,6,0,op==STEP?72:0);}
    private static Reply reply(Command c,int status){return new Reply(c.id(),c.op()==RESERVE?c.id():c.lease(),100,1100,c.world(),c.target(),c.spawn(),status,99,0,72,
        ByteBuffer.wrap(encode(c)).order(ByteOrder.LITTLE_ENDIAN).getLong(200),c.position(),c.delta(),c.health(),0,c.shapeRevision());}
    private static void lost(CompletableFuture<Reply> result,boolean maybe){
        try{result.join();throw new AssertionError("expected transport loss");}catch(CompletionException e){check(e.getCause() instanceof WorldMotionRequests.Lost l&&l.mayHaveExecuted()==maybe,"transport loss execution certainty");}
    }
    public static void main(String[] ignored){
        var transport=new Fake();var broker=new WorldMotionRequests((mc,nativeSession)->transport);
        var scope=new WorldMotionRequests.Scope(1,2,7,31,32);broker.attach(scope);
        var first=broker.request(scope,id->command(id,RESERVE,1000));broker.pump(1000);
        check(transport.commands.size()==1&&!first.isDone(),"publish does not imply execution receipt");
        broker.pump(1074);check(transport.commands.size()==1,"bounded retry interval");broker.pump(1075);
        check(transport.commands.size()==2&&transport.commands.get(0).equals(transport.commands.get(1)),"exact retry retains ID and issued time");
        var sent=transport.commands.get(0);transport.replies.add(reply(sent,0));broker.pump(1080);check(!first.isDone(),"pending receipt is not completion");
        var other=command(sent.id(),RESERVE,1001);transport.replies.add(reply(other,1));broker.pump(1081);
        check(!first.isDone()&&broker.stats().invalid()==1,"payload hash protects response association");
        transport.replies.add(reply(sent,1));broker.pump(1100);check(first.join().status()==1&&broker.pending()==0,"prepared response completes request");
        transport.replies.add(reply(sent,1));broker.pump(1101);check(broker.stats().replies()==1,"duplicate response is ignored");
        var more=broker.request(scope,id->command(id,STEP,1102));broker.pump(1102);check(transport.commands.getLast().id()==2,"strict request ID allocation");
        var changed=new WorldMotionRequests.Scope(1,2,8,31,32);broker.attach(changed);lost(more,true);
        broker.attach(scope);var next=broker.request(scope,id->command(id,RESERVE,1103));broker.pump(1103);
        check(transport.commands.getLast().id()==3,"world reset retains peer ledger high-water ID");
        broker.attach(null);lost(next,true);check(transport.closed,"scope end closes only owned mapping");

        var fullTransport=new Fake();fullTransport.room=false;var full=new WorldMotionRequests((mc,nativeSession)->fullTransport);full.attach(scope);
        var pending=new ArrayList<CompletableFuture<Reply>>();for(int i=0;i<32;i++)pending.add(full.request(scope,id->command(id,RESERVE,1000)));
        lost(full.request(scope,id->command(id,RESERVE,1000)),false);full.pump(1000);check(fullTransport.commands.isEmpty(),"full ring retains unsent requests");
        fullTransport.room=true;full.pump(1001);check(fullTransport.commands.size()==32,"capacity resumes without losing pending requests");
        for(int i=0;i<32;i++)check(fullTransport.commands.get(i).id()==i+1,"unpublished requests retain FIFO ID ordering");
        full.pump(2500);for(var p:pending)lost(p,true);check(full.pending()==0&&full.stats().expired()==32,"unacknowledged effects expire with explicit uncertainty");
        var unsent=new Fake();unsent.room=false;var timeout=new WorldMotionRequests((mc,nativeSession)->unsent);timeout.attach(scope);
        var never=timeout.request(scope,id->command(id,RESERVE,1000));timeout.pump(2500);lost(never,false);

        var callbacks=new Fake();var reentrant=new WorldMotionRequests((mc,nativeSession)->callbacks);reentrant.attach(scope);
        var p=reentrant.request(scope,id->command(id,RESERVE,1000));
        var q=p.thenCompose(r->reentrant.request(scope,id->command(id,STEP,1100)));reentrant.pump(1000);
        callbacks.replies.add(reply(callbacks.commands.getFirst(),1));reentrant.pump(1100);reentrant.pump(1101);
        check(callbacks.commands.size()==2&&callbacks.commands.getLast().op()==STEP&&!q.isDone(),"receipt continuation can enqueue next bounded operation");
        reentrant.close();lost(q,true);
        var broken=new Fake();broken.fail=true;var failure=new WorldMotionRequests((mc,nativeSession)->broken);failure.attach(scope);
        var uncertain=failure.request(scope,id->command(id,RESERVE,1000));failure.pump(1000);lost(uncertain,true);
        check(failure.stats().failures()==1&&failure.pending()==0&&broken.closed,"transport exception cannot strand a request or mapping");
        var unavailable=new WorldMotionRequests((mc,nativeSession)->{throw new IllegalArgumentException("ABI");});unavailable.attach(scope);
        var missing=unavailable.request(scope,id->command(id,RESERVE,1000));unavailable.pump(1000);lost(missing,false);unavailable.pump(1001);
        check(unavailable.stats().failures()==1,"failed mapping open backs off without a frame loop exception");
        var pausedTransport=new Fake();var pausedBroker=new WorldMotionRequests((mc,nativeSession)->pausedTransport);pausedBroker.attach(scope);
        var release=pausedBroker.request(scope,id->command(id,RELEASE,1000));
        var step=pausedBroker.request(scope,id->command(id,STEP,1000));
        var effect=pausedBroker.request(scope,id->command(id,DAMAGE,1000));pausedBroker.pump(1000);
        var releasePacket=pausedTransport.commands.getFirst();pausedTransport.flags=PAUSED;
        for(long now=1100;now<=16000;now+=100)pausedBroker.pump(now);
        check(!release.isDone()&&pausedBroker.pending()==1,"15s continuously observed pause retains only the published handback receipt");lost(step,true);lost(effect,true);
        check(pausedTransport.commands.stream().filter(c->c.op()==RELEASE).allMatch(releasePacket::equals),"pause cannot change the stop ID, timestamp or payload on retry");
        pausedTransport.flags=0;pausedBroker.pump(16100);pausedTransport.replies.add(reply(releasePacket,3));pausedBroker.pump(16200);
        check(release.join().status()==3&&pausedBroker.pending()==0,"real handback receipt completes after long pause without new movement authority");
        var cancel=pausedBroker.request(scope,id->command(id,CANCEL,17000));pausedBroker.pump(17000);pausedTransport.flags=PAUSED;
        for(long now=17100;now<=20000;now+=100)pausedBroker.pump(now);
        check(!cancel.isDone(),"published cancel also waits for actual resumed ticks");pausedTransport.missingStatus=true;
        for(long now=20100;now<=21700;now+=100)pausedBroker.pump(now);
        lost(cancel,true);check(pausedBroker.pending()==0,"missing pause proof restores the bounded receipt timeout");
        pausedTransport.missingStatus=false;pausedTransport.room=false;
        var unpublished=pausedBroker.request(scope,id->command(id,RELEASE,22000));
        for(long now=22000;now<=23500;now+=100)pausedBroker.pump(now);
        lost(unpublished,false);check(pausedBroker.pending()==0,"unpublished stop cannot acquire indefinite paused lifetime");
        pausedTransport.room=true;var scoped=pausedBroker.request(scope,id->command(id,CANCEL,24000));pausedBroker.pump(24000);pausedBroker.pump(24100);pausedBroker.close();lost(scoped,true);
        broker.close();full.close();timeout.close();System.out.println("World motion requests PASS "+checks+" assertions");
    }
}
