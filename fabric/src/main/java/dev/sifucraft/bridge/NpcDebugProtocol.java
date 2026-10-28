package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Opt-in local developer requests, independent of gameplay movement/effect credentials. */
public final class NpcDebugProtocol {
    private NpcDebugProtocol(){}
    public static final int MAGIC=0x444E4353,VERSION=1,REQUEST_BYTES=96,REPLY_BYTES=128;
    public static final int MOVE=1,SPAWN=2,CLEAR=3,APPLIED=1,REFUSED=2,FAILED=3;
    public record Request(int op,int kind,long id,long issued,long nativeSession,long peer,int world,int target,int spawn,
                          float x,float y,float z,long map,long save){}
    public record Reply(int status,int op,long id,long nativeSession,long peer,int world,int target,int spawn,
                        float x,float y,float z,float health,String reason){}
    public static byte[] encode(Request r){
        if(r.id==0||r.issued<=0||r.nativeSession==0||r.peer==0||r.world==0||r.map==0||r.save==0||
           !Float.isFinite(r.x)||!Float.isFinite(r.y)||!Float.isFinite(r.z)||Math.abs(r.x)>10000000||Math.abs(r.y)>10000000||Math.abs(r.z)>10000000||
           !(r.op==MOVE&&r.kind==0&&r.target>0&&r.spawn>0||r.op==SPAWN&&(r.kind==1||r.kind==2)&&r.target==0&&r.spawn==0||
             r.op==CLEAR&&r.kind==0&&r.target==0&&r.spawn==0&&r.x==0&&r.y==0&&r.z==0))throw new IllegalArgumentException("invalid NPC debug request");
        return ByteBuffer.allocate(REQUEST_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(VERSION).putInt(r.op).putInt(r.kind)
            .putLong(r.id).putLong(r.issued).putLong(r.nativeSession).putLong(r.peer).putInt(r.world).putInt(r.target).putInt(r.spawn).putInt(0)
            .putFloat(r.x).putFloat(r.y).putFloat(r.z).putInt(0).putLong(r.map).putLong(r.save).array();
    }
    public static Reply decode(byte[] bytes,Request expected){
        if(bytes.length!=REPLY_BYTES)return null;
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt()!=MAGIC||b.getInt()!=VERSION)return null;
        int status=b.getInt(),op=b.getInt();long id=b.getLong(),nativeSession=b.getLong(),peer=b.getLong();
        int world=b.getInt(),target=b.getInt(),spawn=b.getInt(),reserved=b.getInt();
        float x=b.getFloat(),y=b.getFloat(),z=b.getFloat(),health=b.getFloat();byte[] reason=new byte[56];b.get(reason);
        int end=0;while(end<reason.length&&reason[end]!=0){if(reason[end]<32||reason[end]>126)return null;end++;}
        if(end==reason.length||reserved!=0||status<APPLIED||status>FAILED||op!=expected.op||id!=expected.id||nativeSession!=expected.nativeSession||peer!=expected.peer||world!=expected.world||
           !Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(health))return null;
        if(status==APPLIED&&op!=CLEAR&&(target<=0||spawn<=0||health<=0||op==MOVE&&(target!=expected.target||spawn!=expected.spawn)))return null;
        return new Reply(status,op,id,nativeSession,peer,world,target,spawn,x,y,z,health,new String(reason,0,end,StandardCharsets.US_ASCII));
    }
}
