package dev.sifucraft.blocks;

import dev.sifucraft.bridge.WorldMotionRequests;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Integrated client presentation follows a delayed physical transaction's server progress. */
public final class PistonTimeline {
    private PistonTimeline(){}
    private record Sample(PistonMovingBlockEntity source,WorldMotionRequests.Scope scope,BlockState state,Direction direction,
                          boolean extending,boolean head,float before,float after,long at){}
    private static final ConcurrentHashMap<BlockPos,Sample> samples=new ConcurrentHashMap<>();
    public static void track(PistonMovingBlockEntity piston,WorldMotionRequests.Scope scope){
        float progress=piston.getProgress(1);samples.put(piston.getBlockPos(),new Sample(piston,scope,piston.getMovedState(),piston.getDirection(),piston.isExtending(),piston.isSourcePiston(),progress,progress,System.nanoTime()));
    }
    public static void updated(PistonMovingBlockEntity piston){
        var sample=samples.get(piston.getBlockPos());if(sample==null||sample.source!=piston)return;
        if(piston.isRemoved()){samples.remove(piston.getBlockPos(),sample);return;}
        float next=piston.getProgress(1);
        if(next!=sample.after)samples.replace(piston.getBlockPos(),sample,new Sample(piston,sample.scope,sample.state,sample.direction,sample.extending,sample.head,sample.after,next,System.nanoTime()));
    }
    /** Null means the ordinary client piston tick/renderer owns this unrelated BE. */
    public static Float progress(PistonMovingBlockEntity piston){
        var level=piston.getLevel();if(level==null||!level.isClientSide()||level.dimension()!=Level.OVERWORLD)return null;
        var sample=samples.get(piston.getBlockPos());var world=WorldMotionWorld.current();
        if(sample==null||world==null||!sample.scope.equals(world.scope())||sample.state!=piston.getMovedState()||
            sample.direction!=piston.getDirection()||sample.extending!=piston.isExtending()||sample.head!=piston.isSourcePiston())return null;
        double part=Math.clamp((System.nanoTime()-sample.at)/50_000_000.0,0,1);
        return (float)(sample.before+(sample.after-sample.before)*part);
    }
    public static void prune(MinecraftServer server){samples.entrySet().removeIf(e->{var source=e.getValue().source;var level=source.getLevel();
        return source.isRemoved()||!(level instanceof net.minecraft.server.level.ServerLevel sl)||sl.getServer()!=server||level.getBlockEntity(e.getKey())!=source;});}
    public static java.util.Set<BlockPos> positions(){return java.util.Set.copyOf(samples.keySet());}
    public static void stop(){samples.clear();}
}
