package dev.sifucraft.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Identifies the actual MC block in the installed Entity.isInWall eye query.
 * Imported Native collision shapes are not BlockStates and cannot supply proof. */
public final class SuffocationContacts {
    private SuffocationContacts(){}
    public static BlockPos block(Entity entity){
        float width=entity.getBbWidth()*.8f;
        var eye=AABB.ofSize(entity.getEyePosition(),width,1e-6,width);
        for(var pos:BlockPos.betweenClosed(eye)){
            if(!entity.level().hasChunkAt(pos))continue;
            var state=entity.level().getBlockState(pos);
            if(!state.isAir()&&state.isSuffocating(entity.level(),pos)&&
                Shapes.joinIsNotEmpty(state.getCollisionShape(entity.level(),pos).move(pos),Shapes.create(eye),BooleanOp.AND))return pos.immutable();
        }
        return null;
    }
}
