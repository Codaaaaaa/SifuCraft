package dev.sifucraft.blocks;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Horizontal, no-step preflight. Native capsule sweep remains the final authority. No entity changes. */
public record PistonCollisionPlan(PistonMotionLimit.State budget,Vec3 requested,Vec3 limited,Vec3 collided,AABB before,AABB after) {
    public static PistonCollisionPlan compute(Entity entity,Vec3 requested,PistonMotionLimit.State budget,SifuCollision.Active scene) {
        if(!(entity.level() instanceof ServerLevel level)||!level.getServer().isSameThread()||scene==null||requested==null||requested.y!=0)
            throw new IllegalArgumentException("horizontal server-thread piston plan required");
        var box=entity.getBoundingBox();
        if(!valid(box))throw new IllegalArgumentException("invalid piston body");
        var limit=PistonMotionLimit.plan(budget,level.getGameTime(),requested);
        return SceneBlockSupport.withScene(scene,()->SifuCollision.withQueryView(scene,()->{
            var shapes=level.getEntityCollisions(entity,box.expandTowards(limit.limited()));
            var collided=Entity.collideBoundingBox(entity,limit.limited(),box,level,shapes);
            return new PistonCollisionPlan(limit.state(),requested,limit.limited(),collided,box,box.move(collided));
        }));
    }
    private static boolean valid(AABB b){
        return Double.isFinite(b.minX)&&Double.isFinite(b.minY)&&Double.isFinite(b.minZ)&&Double.isFinite(b.maxX)&&Double.isFinite(b.maxY)&&Double.isFinite(b.maxZ)&&
            b.getXsize()>0&&b.getYsize()>0&&b.getZsize()>0&&b.getXsize()<=20&&b.getYsize()<=40&&b.getZsize()<=20;
    }
}
