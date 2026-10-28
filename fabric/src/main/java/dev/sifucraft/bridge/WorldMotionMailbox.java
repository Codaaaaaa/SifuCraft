package dev.sifucraft.bridge;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/** Broker-serialized IPC. A full ring returns backpressure and retains every unconsumed packet. */
public final class WorldMotionMailbox implements WorldMotionRequests.Transport {
    private static final VarHandle INT=JAVA_INT.varHandle(),LONG=JAVA_LONG.varHandle();
    private final SharedMapping mapping;
    private final MemorySegment memory;
    private final long mc,nativeSession;
    public record PlayerInfo(SifuFrame.Vec3 position,float radius,float halfHeight,float health){
        public boolean valid(){return position!=null&&Float.isFinite(position.x())&&Float.isFinite(position.y())&&Float.isFinite(position.z())&&Float.isFinite(radius)&&radius>=10&&radius<=100&&Float.isFinite(halfHeight)&&halfHeight>=radius&&halfHeight<=200&&Float.isFinite(health)&&health>0;}
    }
    public record NativeStatus(long capabilities,long atMs,int world,int flags,long playerStamp,long shapeRevision,PlayerInfo player){
        public NativeStatus(long capabilities,long atMs,int world,int flags,long playerStamp,long shapeRevision){this(capabilities,atMs,world,flags,playerStamp,shapeRevision,null);}
    }
    public WorldMotionMailbox(long mc,long nativeSession){
        mapping=SharedMapping.open(mappingName(mc,nativeSession),MAPPING_BYTES);memory=mapping.segment();this.mc=mc;this.nativeSession=nativeSession;
        try{initialize();}catch(RuntimeException e){mapping.close();throw e;}
    }
    WorldMotionMailbox(MemorySegment memory,long mc,long nativeSession){this.memory=memory;mapping=null;this.mc=mc;this.nativeSession=nativeSession;initialize();}
    private void initialize(){
        mappingName(mc,nativeSession);if(memory.byteSize()!=MAPPING_BYTES)throw new IllegalArgumentException("world mapping size");
        int magic=(int)INT.getAcquire(memory,0L);
        if(magic!=0){if(!matches())throw new IllegalArgumentException("world mapping ABI/session");return;}
        memory.fill((byte)0);memory.set(JAVA_INT,4,VERSION);memory.set(JAVA_INT,8,MAPPING_BYTES);memory.set(JAVA_INT,12,SLOT_BYTES);
        memory.set(JAVA_LONG,16,mc);memory.set(JAVA_LONG,24,nativeSession);memory.set(JAVA_LONG,32,LAYOUT_HASH);INT.setRelease(memory,0L,MAGIC);
    }
    private boolean matches(){return (int)INT.getAcquire(memory,0L)==MAGIC&&memory.get(JAVA_INT,4)==VERSION&&memory.get(JAVA_INT,8)==MAPPING_BYTES&&
        memory.get(JAVA_INT,12)==SLOT_BYTES&&memory.get(JAVA_LONG,16)==mc&&memory.get(JAVA_LONG,24)==nativeSession&&memory.get(JAVA_LONG,32)==LAYOUT_HASH;}
    public boolean publish(Command command,long now){
        byte[] bytes=encode(command);if(!matches())return false;
        LONG.setRelease(memory,72L,now);
        for(int i=0;i<SLOTS;i++){
            long at=HEADER_BYTES+(long)i*SLOT_BYTES;
            if(!INT.compareAndSet(memory,at,FREE,WRITING))continue;
            try{
                memory.asSlice(at+4,SLOT_BYTES-4).fill((byte)0);memory.set(JAVA_LONG,at+8,command.id());
                MemorySegment.copy(MemorySegment.ofArray(bytes),0,memory,at+16,COMMAND_BYTES);INT.setRelease(memory,at,READY);return true;
            }catch(RuntimeException e){INT.setRelease(memory,at,FREE);throw e;}
        }
        return false;
    }
    public Reply poll(){
        if(!matches())return null;long found=-1,sequence=0;
        for(int i=0;i<SLOTS;i++){
            long at=HEADER_BYTES+(long)SLOTS*SLOT_BYTES+(long)i*SLOT_BYTES;
            if((int)INT.getAcquire(memory,at)!=READY)continue;long seq=memory.get(JAVA_LONG,at+8);
            if(found<0||Long.compareUnsigned(seq,sequence)<0){found=at;sequence=seq;}
        }
        if(found<0||!INT.compareAndSet(memory,found,READY,READING))return null;
        try{
            if(memory.get(JAVA_INT,found+4)!=0)throw new IllegalArgumentException("reserved world reply slot");
            byte[] bytes=new byte[REPLY_BYTES];MemorySegment.copy(memory,found+16,MemorySegment.ofArray(bytes),0,REPLY_BYTES);return decodeReply(bytes);
        }finally{INT.setRelease(memory,found,FREE);}
    }
    public NativeStatus status(long now){
        if(!matches())return null;long seq=(long)LONG.getAcquire(memory,80L);if((seq&1)!=0)return null;
        long caps=memory.get(JAVA_LONG,40),time=memory.get(JAVA_LONG,48),stamp=memory.get(JAVA_LONG,88),revision=memory.get(JAVA_LONG,96);int world=memory.get(JAVA_INT,56),flags=memory.get(JAVA_INT,60);
        var player=(caps&(PLAYER_MACHINE_CAPABILITY|PLAYER_FLUID_CAPABILITY))==0?null:new PlayerInfo(new SifuFrame.Vec3(memory.get(JAVA_FLOAT,104),memory.get(JAVA_FLOAT,108),memory.get(JAVA_FLOAT,112)),memory.get(JAVA_FLOAT,116),memory.get(JAVA_FLOAT,120),memory.get(JAVA_FLOAT,124));
        VarHandle.acquireFence();
        return seq!=(long)LONG.getAcquire(memory,80L)||time<=0||now<time||now-time>500||world==0||(flags&~(PAUSED|SCRIPTED))!=0||player!=null&&(!player.valid()||(int)(stamp>>>32)<=0||(int)stamp<=0)?null:new NativeStatus(caps,time,world,flags,stamp,revision,player);
    }
    @Override public void close(){if(mapping!=null)mapping.close();}
}
