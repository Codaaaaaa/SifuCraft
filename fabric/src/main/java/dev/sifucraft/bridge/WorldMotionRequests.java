package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;

/** Bounded server-to-client request broker. No server thread waits for IPC or touches a mapping. */
public final class WorldMotionRequests implements AutoCloseable {
    public record Scope(long mc,long nativeSession,int world,long save,long map) {
        public Scope {if(mc==0||nativeSession==0||world==0||save==0||map==0)throw new IllegalArgumentException("world motion scope");}
    }
    public interface Transport extends AutoCloseable {
        boolean publish(Command command,long now);
        Reply poll();
        WorldMotionMailbox.NativeStatus status(long now);
        @Override void close();
    }
    @FunctionalInterface public interface Factory {Transport open(long mc,long nativeSession);}
    /** Unacknowledged commands may already have executed. Their actor lease still expires natively. */
    public static final class Lost extends RuntimeException {
        private final boolean mayHaveExecuted;
        Lost(String reason,boolean mayHaveExecuted){super(reason);this.mayHaveExecuted=mayHaveExecuted;}
        public boolean mayHaveExecuted(){return mayHaveExecuted;}
    }
    private static final int CAPACITY=32;
    private static final long RETRY_MS=75,EXPIRE_MS=1500;
    private static final class Pending {
        final Command command;final long hash;final CompletableFuture<Reply> result=new CompletableFuture<>();
        boolean published,attempted;long lastSent;
        Pending(Command c){command=c;hash=java.nio.ByteBuffer.wrap(encode(c)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(200);}
    }
    private final Factory factory;
    private final LinkedHashMap<Long,Pending> pending=new LinkedHashMap<>();
    private Scope scope;
    private Transport transport;
    private long next=1,mc,nativeSession;
    private WorldMotionMailbox.NativeStatus status;
    private long replies,retries,invalid,expired,failures,nextOpen;
    private String lastFailure="";
    public WorldMotionRequests(){this((mc,nativeSession)->new WorldMotionMailbox(mc,nativeSession));}
    public WorldMotionRequests(Factory factory){this.factory=java.util.Objects.requireNonNull(factory);}
    public synchronized Scope scope(){return scope;}
    public synchronized WorldMotionMailbox.NativeStatus status(){return status;}
    public synchronized int pending(){return pending.size();}
    public record Stats(long replies,long retries,long invalid,long expired,long failures){}
    public synchronized Stats stats(){return new Stats(replies,retries,invalid,expired,failures);}
    public synchronized String lastFailure(){return lastFailure;}

    /** Client thread only. Input ownership/anchor epoch is deliberately absent from this scope. */
    public void attach(Scope wanted){
        List<Pending> lost;
        synchronized(this){
            if(java.util.Objects.equals(scope,wanted))return;
            lost=new ArrayList<>(pending.values());pending.clear();status=null;nextOpen=0;
            if(wanted==null||wanted.mc!=mc||wanted.nativeSession!=nativeSession){
                if(transport!=null)transport.close();transport=null;
                if(wanted!=null&&(wanted.mc!=mc||wanted.nativeSession!=nativeSession)){mc=wanted.mc;nativeSession=wanted.nativeSession;next=1;}
            }
            scope=wanted;
        }
        lose(lost,"world scope ended");
    }
    /** Any caller thread. Build and enqueue atomically so request IDs reach Native in order. */
    public CompletableFuture<Reply> request(Scope expected,LongFunction<Command> create){
        synchronized(this){
            if(scope==null||!scope.equals(expected))return CompletableFuture.failedFuture(new Lost("world scope unavailable",false));
            if(pending.size()>=CAPACITY)return CompletableFuture.failedFuture(new Lost("world request backpressure",false));
            Command command=create.apply(next++);validate(command);
            if(command.id()!=next-1||command.world()!=scope.world||command.save()!=scope.save||command.map()!=scope.map)
                throw new IllegalArgumentException("world request does not match broker scope");
            var queued=new Pending(command);pending.put(command.id(),queued);return queued.result;
        }
    }
    /** Client thread only. Future continuations run after releasing the broker lock. */
    public void pump(long now){
        var done=new ArrayList<Runnable>();
        synchronized(this){
            if(scope==null)return;
            if(transport==null&&now<nextOpen)return;
            try {
            if(transport==null)transport=factory.open(mc,nativeSession);
            status=transport.status(now);
            if(status!=null&&status.world()!=scope.world)status=null;
            for(int i=0;i<64;++i){
                Reply reply;
                try{reply=transport.poll();}catch(IllegalArgumentException e){invalid++;continue;}
                if(reply==null)break;
                var p=pending.get(reply.id());if(p==null)continue;
                var c=p.command;
                if(reply.sourceHash()!=p.hash||reply.world()!=c.world()||reply.target()!=c.target()||reply.spawn()!=c.spawn()||
                    reply.lease()!=(c.op()==RESERVE?c.id():c.lease())){invalid++;continue;}
                if(reply.status()==0)continue;
                pending.remove(reply.id());replies++;done.add(()->p.result.complete(reply));
            }
            var iterator=pending.values().iterator();
            while(iterator.hasNext()){
                var p=iterator.next();
                if(now<p.command.issuedMs()||now-p.command.issuedMs()>=EXPIRE_MS){
                    iterator.remove();expired++;done.add(()->p.result.completeExceptionally(new Lost("world receipt timeout",p.published)));continue;
                }
                if(p.published&&now-p.lastSent<RETRY_MS)continue;
                p.attempted=true;boolean accepted=transport.publish(p.command,now);p.attempted=false;
                if(!accepted)break;
                if(p.published)retries++;p.published=true;p.lastSent=now;
            }
            }catch(RuntimeException|LinkageError e){
                failures++;lastFailure=e.toString();status=null;nextOpen=now+1000;
                if(transport!=null)try{transport.close();}catch(RuntimeException close){lastFailure+="; close: "+close;}
                transport=null;
                for(var p:pending.values())done.add(()->p.result.completeExceptionally(new Lost("world transport failed",p.published||p.attempted)));
                pending.clear();
            }
        }
        done.forEach(Runnable::run);
    }
    private static void lose(List<Pending> lost,String reason){for(var p:lost)p.result.completeExceptionally(new Lost(reason,p.published));}
    @Override public void close(){attach(null);}
}
