package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reliable world commands, independent of presentation and player input epochs. */
public final class WorldMotionProtocol {
    private WorldMotionProtocol(){}
    public static final int MAGIC=0x4D574353,VERSION=3,SLOTS=32,SLOT_BYTES=256,HEADER_BYTES=128;
    public static final int MAPPING_BYTES=HEADER_BYTES+2*SLOTS*SLOT_BYTES,COMMAND_BYTES=208,REPLY_BYTES=128;
    public static final int FREE=0,WRITING=1,READY=2,READING=3,RESERVE=1,STEP=2,RELEASE=3,CANCEL=4,DAMAGE=5,RENEW=6;
    public static final int MACHINE=1,FLUID=2,WORLD_EFFECT=3,PLAYER=1;
    public static final int PAUSED=1,SCRIPTED=2;
    public static final long MOTION_CAPABILITY=1,DAMAGE_CAPABILITY=2,PAUSE_CAPABILITY=4;
    private static final int[] LAYOUT={MAGIC,VERSION,SLOTS,SLOT_BYTES,HEADER_BYTES,MAPPING_BYTES,208,128,
        0,4,8,12,16,24,32,40,48,56,60,64,72,80,88,96,104,
        0,4,8,16,24,32,40,48,56,64,72,80,88,92,96,100,104,116,128,132,136,140,144,156,160,164,168,176,184,192,200,
        0,8,16,24,32,36,40,44,48,52,56,64,72,84,96,100,104,112,116,120,FREE,WRITING,READY,READING,
        1,2,3,4,5,6,1,2,3,0,1,2,3,4,5,6,7,8,9,10,11,12,13,PLAYER,PAUSED,SCRIPTED,1,2,4};
    public static final long LAYOUT_HASH;
    static {long h=0xcbf29ce484222325L;for(int v:LAYOUT)for(int s=0;s<32;s+=8){h^=v>>>s&255;h*=0x100000001b3L;}LAYOUT_HASH=h;}
    public static String mappingName(long mc,long nativeSession){
        if(mc==0||nativeSession==0)throw new IllegalArgumentException("motion sessions");
        return "Local\\SifuCraft.WorldMotion.%016X.%016X".formatted(mc,nativeSession);
    }
    public record Command(int op,int source,long id,long lease,long issuedMs,long sourceHi,long sourceLo,long action,long tick,long save,long map,long shapeRevision,
                          int world,int target,int spawn,int durationMs,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,float health,float radius,float halfHeight,int flags,
                          int blockX,int blockY,int blockZ,float progress,float nextProgress,float amount,long stateHash,long sectionRevision,long actorStamp,long componentToken){}
    public record Reply(long id,long lease,long nativeFrame,long atMs,int world,int target,int spawn,int status,int actorSerial,int restored,
                        long componentToken,long sourceHash,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,float health,int flags,long shapeRevision,float rootRadius,float rootHalfHeight){
        public Reply(long id,long lease,long nativeFrame,long atMs,int world,int target,int spawn,int status,int actorSerial,int restored,
                     long componentToken,long sourceHash,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,float health,int flags,long shapeRevision){
            this(id,lease,nativeFrame,atMs,world,target,spawn,status,actorSerial,restored,componentToken,sourceHash,position,delta,health,flags,shapeRevision,0,0);
        }
        /** Prepared has made no move; its delta binds the root capsule to the hit anchor. */
        public SifuFrame.Vec3 rootOffset(){if(status!=1)throw new IllegalStateException("root binding requires Prepared");return delta;}
    }
    private static boolean finite(SifuFrame.Vec3 v){return v!=null&&Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z());}
    public static void validate(Command c){
        if(c==null||c.op<1||c.op>6||c.source<1||c.source>3||c.id==0||c.issuedMs==0||(c.sourceHi|c.sourceLo)==0||c.action==0||c.save==0||c.map==0||c.world==0||
            (c.flags&~PLAYER)!=0||((c.flags&PLAYER)!=0?(c.target!=0||c.spawn!=0||c.actorStamp==0):(c.target<=0||c.spawn==0))||c.durationMs<0||c.durationMs>500||
            !Float.isFinite(c.health)||c.health<0||!Float.isFinite(c.amount)||c.amount<0||c.amount>1e7f||!Float.isFinite(c.radius)||!Float.isFinite(c.halfHeight)||
            c.radius<10||c.radius>100||c.halfHeight<c.radius||c.halfHeight>200||!Float.isFinite(c.progress)||!Float.isFinite(c.nextProgress)||
            c.progress<0||c.nextProgress<c.progress||c.nextProgress>1||!finite(c.position)||!finite(c.delta))throw new IllegalArgumentException("invalid world motion command");
        double length=(double)c.delta.x()*c.delta.x()+(double)c.delta.y()*c.delta.y()+(double)c.delta.z()*c.delta.z();
        int axes=(c.delta.x()!=0?1:0)+(c.delta.y()!=0?1:0)+(c.delta.z()!=0?1:0);
        if(c.op==RESERVE&&(c.durationMs==0||length!=0||c.lease!=0)||c.op==STEP&&(c.lease==0||c.componentToken==0||length<=0||length>100.002||c.source==MACHINE&&axes!=1)||
            (c.op==RELEASE||c.op==CANCEL)&&(c.lease==0||length!=0)||c.op==DAMAGE&&(c.amount<=0||length!=0)||c.op==RENEW&&(c.source!=FLUID||c.lease==0||c.componentToken==0||c.durationMs==0||length!=0)||c.source==MACHINE&&(c.stateHash==0||c.sectionRevision==0))
            throw new IllegalArgumentException("invalid world motion operation");
    }
    public static byte[] encode(Command c){
        validate(c);var b=ByteBuffer.allocate(COMMAND_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(c.op).putInt(c.source).putLong(c.id).putLong(c.lease).putLong(c.issuedMs).putLong(c.sourceHi).putLong(c.sourceLo).putLong(c.action).putLong(c.tick)
            .putLong(c.save).putLong(c.map).putLong(c.shapeRevision).putInt(c.world).putInt(c.target).putInt(c.spawn).putInt(c.durationMs);
        put(b,c.position);put(b,c.delta);b.putFloat(c.health).putFloat(c.radius).putFloat(c.halfHeight).putInt(c.flags)
            .putInt(c.blockX).putInt(c.blockY).putInt(c.blockZ).putFloat(c.progress).putFloat(c.nextProgress).putFloat(c.amount)
            .putLong(c.stateHash).putLong(c.sectionRevision).putLong(c.actorStamp).putLong(c.componentToken);
        b.putLong(hash(b.array(),200));return b.array();
    }
    public static Reply decodeReply(byte[] bytes){
        if(bytes.length!=REPLY_BYTES)throw new IllegalArgumentException("world reply size");var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var r=new Reply(b.getLong(),b.getLong(),b.getLong(),b.getLong(),b.getInt(),b.getInt(),b.getInt(),b.getInt(),b.getInt(),b.getInt(),
            b.getLong(),b.getLong(),get(b),get(b),b.getFloat(),b.getInt(),b.getLong(),b.getFloat(),b.getFloat());
        long digest=b.getLong();
        if(r.id==0||r.atMs==0||r.world==0||r.status<0||r.status>13||r.restored<0||r.restored>7||!finite(r.position)||!finite(r.delta)||
            !Float.isFinite(r.health)||r.health<0||!Float.isFinite(r.rootRadius)||!Float.isFinite(r.rootHalfHeight)||
            (r.rootRadius!=0||r.rootHalfHeight!=0)&&(r.rootRadius<10||r.rootRadius>100||r.rootHalfHeight<r.rootRadius||r.rootHalfHeight>200)||
            r.status==1&&r.rootRadius==0||digest!=hash(bytes,120))throw new IllegalArgumentException("invalid world reply");
        return r;
    }
    private static void put(ByteBuffer b,SifuFrame.Vec3 v){b.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());}
    private static SifuFrame.Vec3 get(ByteBuffer b){return new SifuFrame.Vec3(b.getFloat(),b.getFloat(),b.getFloat());}
    public static long hash(byte[] bytes,int length){long h=0xcbf29ce484222325L;for(int i=0;i<length;i++){h^=bytes[i]&255;h*=0x100000001b3L;}return h;}
}
