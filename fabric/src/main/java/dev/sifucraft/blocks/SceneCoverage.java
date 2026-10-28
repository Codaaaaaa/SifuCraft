package dev.sifucraft.blocks;

import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionProtocol;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.bridge.WorldWriter;
import java.util.LinkedHashMap;
import net.minecraft.world.phys.AABB;

/** Bounded region requests. A receipt acknowledges enqueueing, never certifies empty space. */
public final class SceneCoverage {
    private SceneCoverage(){}
    private record Tile(int x,int y,int z){}
    private static final LinkedHashMap<Tile,Long> recent=new LinkedHashMap<>();
    private static WorldMotionRequests.Scope scope;
    public static synchronized void request(AABB box){
        var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null||(view.nativeStatus().capabilities()&WorldMotionProtocol.COVERAGE_CAPABILITY)==0)return;
        var p=view.placement();if(!p.containsX(box.minX)||!p.containsX(box.maxX))return;
        if(!view.scope().equals(scope)){scope=view.scope();recent.clear();}
        long now=WorldWriter.clockMillis();recent.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>=2000);
        var mid=box.getCenter();var tile=new Tile((int)Math.floor(p.ueX(mid.x)/800),(int)Math.floor(p.ueY(mid.z)/800),(int)Math.floor(p.ueZ(mid.y)/800));
        if(recent.containsKey(tile)||recent.size()>=16||view.requests().pending()>=16)return;
        recent.put(tile,now);var s=scope;
        var center=new SifuFrame.Vec3(tile.x*800+400,tile.y*800+400,tile.z*800+400);
        view.requests().request(s,id->new WorldMotionProtocol.Command(WorldMotionProtocol.COVERAGE,WorldMotionProtocol.WORLD_EFFECT,id,0,now,0x434F564552414745L,1,1,0,s.save(),s.map(),0,s.world(),0,0,0,center,new SifuFrame.Vec3(0,0,0),0,0,0,0,0,0,0,0,0,0,0,0,0,0)).exceptionally(error->null);
    }
}
