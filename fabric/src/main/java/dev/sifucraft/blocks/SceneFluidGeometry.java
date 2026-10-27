package dev.sifucraft.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import dev.sifucraft.collision.SifuCollision;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;

/** Native geometry supplements vanilla fluid rules; it never creates saved wall blocks. */
public final class SceneFluidGeometry {
    private SceneFluidGeometry(){}
    private record View(boolean required,boolean ready,SifuCollision.Active scene){}
    private static final View VANILLA=new View(false,true,null);
    private static volatile View published=VANILLA;
    private static volatile long retainedPending;
    private static final ThreadLocal<View> diagnostic=new ThreadLocal<>();
    /** One coherent render-thread publication, also read by the integrated server. */
    public static void publish(boolean required,boolean ready,SifuCollision.Active scene){
        published=new View(required,ready,scene);
        if(required&&!ready&&scene!=null&&Boolean.getBoolean("sifucraft.fluidSceneSelfTest"))retainedPending++;
    }
    public static long retainedPendingPublications(){return retainedPending;}
    private static View view(BlockGetter level){
        if(!WorldBlocks.ENABLED||!(level instanceof Level world)||world.dimension()!=Level.OVERWORLD)return VANILLA;
        var test=diagnostic.get();if(test!=null)return test;
        var scoped=SceneBlockSupport.scopedScene();if(scoped!=null)return new View(true,true,scoped);
        var current=published;return current.required?current:new View(false,true,SceneBlockSupport.scene(level));
    }
    public static <T> T withUnavailableGeometry(java.util.function.Supplier<T> action){
        if(!Boolean.getBoolean("sifucraft.fluidSceneSelfTest"))throw new IllegalStateException("isolated fluid scene only");
        var old=diagnostic.get();diagnostic.set(new View(true,false,null));
        try{return action.get();}finally{if(old==null)diagnostic.remove();else diagnostic.set(old);}
    }
    public static boolean canOccupy(BlockGetter level,BlockPos pos){
        var query=view(level);if(!query.ready)return false;var scene=query.scene;return scene==null||scene.placement().containsX(pos.getX())&&scene.fluidCell(pos);
    }
    public static boolean available(BlockGetter level){return view(level).ready;}
    /** A machine bucket must not reach through an imported face into a neighbouring cell. */
    public static boolean canDispenseTo(BlockGetter level,BlockPos source,BlockPos target){
        return inActiveMap(level,source)&&canOccupy(level,target)&&canContact(level,source,target);
    }
    /** Native heat cannot cross a map boundary or an imported wall. */
    public static boolean canEmitTo(BlockGetter level,BlockPos source,BlockPos target){
        var query=view(level);if(!query.ready)return false;var scene=query.scene;if(scene==null)return true;
        if(!scene.placement().containsX(source.getX())||!scene.placement().containsX(target.getX())||!scene.fluidCell(source)||!scene.fluidCell(target))return false;
        return scene.clip(net.minecraft.world.phys.Vec3.atCenterOf(source),net.minecraft.world.phys.Vec3.atCenterOf(target))==null;
    }
    public static boolean inActiveMap(BlockGetter level,BlockPos pos){
        var query=view(level);if(!query.ready)return false;var scene=query.scene;return scene==null||scene.placement().containsX(pos.getX());
    }
    /** Contact with water or blue ice is tested against Native geometry only. */
    public static boolean canContact(BlockGetter level,BlockPos from,BlockPos to){
        if(from.equals(to))return true;
        int dx=to.getX()-from.getX(),dy=to.getY()-from.getY(),dz=to.getZ()-from.getZ();
        if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1)return inActiveMap(level,from)&&inActiveMap(level,to);
        var air=Blocks.AIR.defaultBlockState();
        return canPass(Direction.getNearest(dx,dy,dz,null),level,from,air,to,air);
    }
    public static boolean canPass(Direction face,BlockGetter level,BlockPos from,BlockState a,BlockPos to,BlockState b){
        var query=view(level);if(!query.ready)return false;var scene=query.scene;if(scene==null)return true;
        if(!scene.placement().containsX(from.getX())||!scene.placement().containsX(to.getX()))return false;
        if(!scene.fluidCell(from)||!scene.fluidCell(to))return false;
        var source=scene.shapeAt(from);var destination=scene.shapeAt(to);if(source.isEmpty()&&destination.isEmpty())return true;
        source=Shapes.or(a.getCollisionShape(level,from),source);destination=Shapes.or(b.getCollisionShape(level,to),destination);
        return !Shapes.mergedFaceOccludes(source,destination,face);
    }
    public static BlockHitResult bucketRay(Level level,Player player,BlockHitResult vanilla){
        var eye=player.getEyePosition();var end=eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        var query=view(level);if(!query.ready)return BlockHitResult.miss(end,Direction.getApproximateNearest(player.getViewVector(1)),BlockPos.containing(end));
        var scene=query.scene;if(scene==null)return vanilla;
        var nativeHit=scene.clip(eye,end);if(nativeHit==null||vanilla.getType()!=HitResult.Type.MISS&&eye.distanceToSqr(vanilla.getLocation())<=eye.distanceToSqr(nativeHit.getLocation())+1e-9)return vanilla;
        // BlockItem uses the replaceable front cell; BucketItem adds the face
        // itself, so name the virtual backing cell to obtain that same target.
        return new BlockHitResult(nativeHit.getLocation(),nativeHit.getDirection(),nativeHit.getBlockPos().relative(nativeHit.getDirection().getOpposite()),false);
    }
}
