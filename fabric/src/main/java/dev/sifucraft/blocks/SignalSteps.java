package dev.sifucraft.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Grounded walking vibrations for snapshot bodies. Never invokes movement or block effects. */
public final class SignalSteps {
    private Vec3 previous;
    private float distance,next=1;
    public void update(Entity body) {
        if(!WorldBlocks.ENABLED||!(body.level() instanceof ServerLevel level)||!body.noPhysics||!body.isAlive()||body.isRemoved())return;
        var position=body.position();var from=previous;previous=position;
        if(from==null)return;
        var delta=position.subtract(from);
        if(delta.lengthSqr()>16){distance=0;next=1;return;} // Spawn/teleport is not a walked path.
        double travel=delta.horizontalDistance();
        if(travel<1e-7)return;
        var box=body.getBoundingBox();
        var feet=new AABB(box.minX+1e-7,box.minY-.1,box.minZ+1e-7,box.maxX-1e-7,box.minY+.001,box.maxZ-1e-7);
        boolean grounded=false;
        for(var shape:level.getBlockCollisions(body,feet))if(!shape.isEmpty()){grounded=true;break;}
        if(!grounded)return;
        // Installed 26.3 Entity movement uses horizontal length * 0.6 and floor(moveDist)+1.
        distance+=(float)(travel*(double).6f);
        if(distance<=next)return;
        next=(int)distance+1;
        var state=level.getBlockState(BlockPos.containing(position.x,position.y-.2,position.z));
        level.gameEvent(GameEvent.STEP,position,GameEvent.Context.of(body,state));
    }
}
