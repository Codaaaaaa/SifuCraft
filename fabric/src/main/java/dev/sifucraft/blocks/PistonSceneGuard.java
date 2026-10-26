package dev.sifucraft.blocks;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** A Native wall is immovable geometry, not AIR that a piston may push blocks through. */
public final class PistonSceneGuard {
    private PistonSceneGuard(){}
    public static boolean allows(Level level,BlockPos piston,Direction facing,boolean extending,Direction movement,List<BlockPos> pushed) {
        var scene=SceneBlockSupport.scene(level);
        if(!WorldBlocks.ENABLED||scene==null)return true;
        // Keep the complete vanilla action on one immutable geometry snapshot, including branches.
        return SceneBlockSupport.withScene(scene,()->{
            var head=piston.relative(facing);
            // The full-width head plate sweeps every point of this cell while extending.
            if(extending&&SceneBlockSupport.intersects(level,Shapes.block().move(head.getX(),head.getY(),head.getZ())))return false;
            var delta=new Vec3(movement.getStepX(),movement.getStepY(),movement.getStepZ());
            for(var pos:pushed){
                var state=level.getBlockState(pos);
                var shape=Shapes.or(state.getShape(level,pos),state.getCollisionShape(level,pos));
                var swept=Shapes.empty();
                for(var box:shape.toAabbs())swept=Shapes.or(swept,Shapes.create(box.expandTowards(delta)));
                // Existing overlap may move out; only newly occupied volume must be clear.
                var added=Shapes.join(swept,shape,BooleanOp.ONLY_FIRST).move(pos.getX(),pos.getY(),pos.getZ());
                if(SceneBlockSupport.intersects(level,added))return false;
            }
            return true;
        });
    }
}
