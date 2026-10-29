package dev.sifucraft.combat;

import dev.sifucraft.blocks.SceneFluidGeometry;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.material.FluidState;

/** Scoped filtering around the installed Entity path traversal and effect collector. */
public final class BlockEnvironment {
    private BlockEnvironment(){}
    public record Movement(Vec3 from,Vec3 to){}
    public record Contact(String type,BlockPos pos,BlockState state){}
    private record Context(Entity target,List<Movement> path,Consumer<Contact> evidence){}
    private static final ThreadLocal<Context> current=new ThreadLocal<>();
    public static boolean active(Entity target){var c=current.get();return c!=null&&c.target==target;}
    public static List<Movement> path(Entity target){var c=current.get();return c!=null&&c.target==target?c.path:null;}
    /** A water block's full inside shape may be visited before its lower liquid
     * surface is reached. Do not consume that cell's fluid callback too early
     * when one actual move was split into short sweeps. */
    public static boolean mayVisit(Entity target,BlockPos pos,Vec3 from,Vec3 to){
        if(!active(target))return true;
        if(!SceneFluidGeometry.canOccupy(target.level(),pos))return false;
        var state=target.level().getBlockState(pos);var fluid=state.getFluidState();
        if((fluid.is(FluidTags.WATER)||fluid.is(FluidTags.LAVA))&&target.collidedWithFluid(fluid,pos,from,to))return true;
        if(state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE))return target.collidedWithShapeMovingFrom(from,to,state.getEntityInsideCollisionShape(target.level(),pos,target).move(pos.getX(),pos.getY(),pos.getZ()).toAabbs());
        return false;
    }
    public static boolean block(Entity target,BlockPos pos,BlockState state){
        if(!active(target))return true;
        if(!(state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE))||!SceneFluidGeometry.canOccupy(target.level(),pos))return false;
        current.get().evidence.accept(new Contact("minecraft:in_fire",pos.immutable(),state));return true;
    }
    public static boolean fluid(Entity target,BlockPos pos,FluidState fluid){
        if(!active(target))return true;
        if(!(fluid.is(FluidTags.WATER)||fluid.is(FluidTags.LAVA))||!SceneFluidGeometry.canOccupy(target.level(),pos))return false;
        if(fluid.is(FluidTags.LAVA))current.get().evidence.accept(new Contact("minecraft:lava",pos.immutable(),target.level().getBlockState(pos)));
        return true;
    }
    static void apply(Entity target,List<Movement> path,Consumer<Contact> evidence){
        var previous=current.get();current.set(new Context(target,List.copyOf(path),evidence));
        try{target.applyEffectsFromBlocks(target.position(),target.position());}
        finally{if(previous==null)current.remove();else current.set(previous);}
    }
}
