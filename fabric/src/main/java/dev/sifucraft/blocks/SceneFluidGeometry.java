package dev.sifucraft.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;

/** Native geometry supplements vanilla fluid rules; it never creates saved wall blocks. */
public final class SceneFluidGeometry {
    private SceneFluidGeometry(){}
    public static boolean canOccupy(BlockGetter level,BlockPos pos){
        var scene=WorldBlocks.ENABLED?SceneBlockSupport.scene(level):null;return scene==null||scene.fluidCell(pos);
    }
    public static boolean canPass(Direction face,BlockGetter level,BlockPos from,BlockState a,BlockPos to,BlockState b){
        var scene=WorldBlocks.ENABLED?SceneBlockSupport.scene(level):null;if(scene==null)return true;
        if(!scene.fluidCell(from)||!scene.fluidCell(to))return false;
        var source=scene.shapeAt(from);var destination=scene.shapeAt(to);if(source.isEmpty()&&destination.isEmpty())return true;
        source=Shapes.or(a.getCollisionShape(level,from),source);destination=Shapes.or(b.getCollisionShape(level,to),destination);
        return !Shapes.mergedFaceOccludes(source,destination,face);
    }
    public static BlockHitResult bucketRay(Level level,Player player,BlockHitResult vanilla){
        var scene=WorldBlocks.ENABLED?SceneBlockSupport.scene(level):null;if(scene==null)return vanilla;
        var eye=player.getEyePosition();var end=eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        var nativeHit=scene.clip(eye,end);if(nativeHit==null||vanilla.getType()!=HitResult.Type.MISS&&eye.distanceToSqr(vanilla.getLocation())<=eye.distanceToSqr(nativeHit.getLocation())+1e-9)return vanilla;
        // BlockItem uses the replaceable front cell; BucketItem adds the face
        // itself, so name the virtual backing cell to obtain that same target.
        return new BlockHitResult(nativeHit.getLocation(),nativeHit.getDirection(),nativeHit.getBlockPos().relative(nativeHit.getDirection().getOpposite()),false);
    }
}
