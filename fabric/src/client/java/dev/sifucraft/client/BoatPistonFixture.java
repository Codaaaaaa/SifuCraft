package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.combat.BoatPassengers;
import dev.sifucraft.combat.EnemyMirrors;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A full vanilla stroke and retraction, with a separate vanilla boat lane as the reference. */
final class BoatPistonFixture {
    private static final class Rider extends Cow {
        private EntityDimensions size;
        Rider(ServerLevel level,Entity source){super(EntityTypes.COW,level);size=EntityDimensions.scalable(source.getBbWidth(),source.getBbHeight());refreshDimensions();setNoAi(true);setSilent(true);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.8f):size;}
    }
    private final ServerLevel level;
    private final AbstractBoat boat,reference;
    private final EnemyFrame.Key key;
    private final Direction direction;
    private final BlockPos base,referenceBase;
    private final BiConsumer<BlockPos,BlockState> put;
    private final List<Rider> riders=new ArrayList<>();
    private final Vec3 boatStart,nativeStart,referenceStart;
    private final PistonMachines.Stats machinesBefore;
    private final BoatPassengers.Stats passengersBefore;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private long extensionRevision;
    private int stable;
    private boolean interrupted;

    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("boat piston: "+why);}
    BoatPistonFixture(ServerLevel level,AbstractBoat boat,EnemyFrame.Entry enemy,boolean east,BiConsumer<BlockPos,BlockState> put){
        this.level=level;this.boat=boat;this.key=enemy.key();this.put=put;direction=east?Direction.EAST:Direction.WEST;
        var view=WorldMotionWorld.current();var root=BoatPassengers.physicalBounds(key,view);
        require(root!=null&&BoatPassengers.seated(key),"actual seated root binding before setup");
        var hull=boat.getBoundingBox();
        // The base ends outside the hull; the head's one-block sweep reaches it.
        // floor(boat centre) +/- a constant can either overlap or miss the hull.
        int x=east?(int)Math.floor(hull.minX)-1:(int)Math.ceil(hull.maxX);
        base=new BlockPos(x,(int)Math.floor(hull.minY),(int)Math.floor(boat.getZ()));
        BlockPos referenceAt=null;Vec3 referenceOffset=null;
        for(int z:List.of(2,-2)){
            var candidate=base.offset(0,0,z);var offset=new Vec3(0,0,z);boolean clear=PistonSceneGuard.allows(level,candidate,direction,true,direction,List.of());
            for(var shape:level.getBlockCollisions(boat,hull.move(offset).expandTowards(direction.getStepX(),0,0).deflate(.001)))if(!shape.isEmpty())clear=false;
            if(clear){referenceAt=candidate;referenceOffset=offset;break;}
        }
        require(referenceAt!=null,"reference lane and full head sweep clear actual scene geometry");referenceBase=referenceAt;
        var baseBox=new AABB(base);var sweep=new AABB(base.relative(direction));
        require(!baseBox.intersects(hull)&&!baseBox.intersects(root),"base clears hull and actual root: base="+baseBox+" hull="+hull+" root="+root);
        require(sweep.intersects(hull),"head sweep intersects hull: sweep="+sweep+" hull="+hull);
        for(var rider:boat.getPassengers())require(!baseBox.intersects(rider.getBoundingBox()),"base clears every seat");
        require(!new AABB(base.above()).intersects(root),"power source clears actual root");
        var swept=hull.expandTowards(new Vec3(direction.getStepX(),0,0));
        for(var shape:level.getBlockCollisions(boat,swept.deflate(.001)))require(shape.isEmpty(),"unobstructed actual stroke before placing piston: hull="+hull+" sweep="+swept+" obstacle="+shape);
        reference=(AbstractBoat)EntityTypes.OAK_BOAT.create(level,EntitySpawnReason.COMMAND);require(reference!=null,"reference boat factory");
        reference.setPos(boat.position().add(referenceOffset));reference.setYRot(boat.getYRot());reference.setDeltaMovement(boat.getDeltaMovement());
        require(level.addFreshEntity(reference),"separate vanilla reference boat");
        for(var source:boat.getPassengers()){
            var rider=new Rider(level,source);riders.add(rider);rider.setPos(source.position().add(referenceOffset));
            require(level.addFreshEntity(rider)&&rider.startRiding(reference),"reference seat capacity");
        }
        boatStart=boat.position();nativeStart=EnemyMirrors.hitAttacker(key.id(),key.spawn()).position();referenceStart=reference.position();
        machinesBefore=PistonMachines.stats();passengersBefore=BoatPassengers.stats();
        for(var at:List.of(base,referenceBase)){
            require(level.getBlockState(at.relative(direction)).isAir()||level.getBlockState(at.relative(direction)).is(Blocks.WATER),"head cell has no payload");
            put.accept(at.relative(direction),level.getBlockState(at.relative(direction)));
            put.accept(at,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,direction));
        }
        report.put("direction",direction.toString());report.put("base",base.toString());report.put("reference_base",referenceBase.toString());report.put("initial_hull",hull.toString());report.put("initial_native_root",root.toString());
        report.put("head_sweep",sweep.toString());report.put("initial_base_overlap",false);
        log("setup");
    }
    private boolean acknowledged(){
        var v=WorldMotionWorld.current();return v!=null&&v.nativeStatus()!=null&&v.shapesCurrent()&&v.geometry().index()==WorldBlocks.index().revision()&&
            v.geometry().motion()>=ShapeChanges.revision()&&v.nativeStatus().shapeRevision()==v.geometry().revision();
    }
    void power(boolean on){for(var at:List.of(base,referenceBase))put.accept(at.above(),(on?Blocks.REDSTONE_BLOCK:Blocks.AIR).defaultBlockState());stable=0;}
    private boolean settled(BlockPos at,boolean extended){
        var state=level.getBlockState(at);var head=level.getBlockState(at.relative(direction));
        return state.is(Blocks.PISTON)&&state.getValue(BlockStateProperties.EXTENDED)==extended&&
            (extended?head.is(Blocks.PISTON_HEAD)&&head.getValue(BlockStateProperties.FACING)==direction:head.isAir()||head.is(Blocks.WATER))&&
            !(level.getBlockEntity(at) instanceof PistonMovingBlockEntity)&&!(level.getBlockEntity(at.relative(direction)) instanceof PistonMovingBlockEntity);
    }
    boolean completed(boolean extended){
        if(!settled(base,extended)||!settled(referenceBase,extended)||PistonMachines.stats().pending()!=0||PistonMachines.machineActive(key)||
            !BoatPassengers.seated(key)||!acknowledged()){stable=0;return false;}
        if(++stable<5)return false;
        var proxy=EnemyMirrors.hitAttacker(key.id(),key.spawn());require(proxy!=null&&proxy.getVehicle()==boat,"same actual rider after mechanical handoff");
        if(extended){
            double actual=boat.position().subtract(boatStart).x*direction.getStepX(),expected=reference.position().subtract(referenceStart).x*direction.getStepX();
            double nativeMoved=proxy.position().subtract(nativeStart).x*direction.getStepX();
            require(expected>.001,"original reference stroke contacts its boat");
            require(Math.abs(actual-expected)<.02,"full stroke matches vanilla reference actual="+actual+" reference="+expected);
            require(nativeMoved>.001&&Math.abs(nativeMoved-actual)<.02,"actual root follows completed boat stroke native="+nativeMoved+" boat="+actual);
            var root=BoatPassengers.physicalBounds(key,WorldMotionWorld.current());require(root!=null,"resumed physical binding");
            var head=base.relative(direction);
            for(var box:level.getBlockState(head).getCollisionShape(level,head).toAabbs())
                require(!box.move(head).intersects(root.deflate(.001)),"final piston head clears actual reserved root: head="+box.move(head)+" root="+root);
            var stats=PistonMachines.stats();require(stats.committed()>machinesBefore.committed()&&stats.released()>machinesBefore.released(),"production machine commits and releases");
            require(stats.releaseFailed()==machinesBefore.releaseFailed(),"every machine release restores ownership");
            extensionRevision=WorldMotionWorld.current().geometry().revision();
            report.put("boat_moved_m",actual);report.put("reference_moved_m",expected);report.put("native_moved_m",nativeMoved);
            report.put("extended_head",level.getBlockState(base.relative(direction)).toString());report.put("extension_geometry_ack",extensionRevision);report.put("final_native_root",root.toString());
        }else{
            long revision=WorldMotionWorld.current().geometry().revision();require(revision>extensionRevision,"retraction replaces old head collision and is ACKed");
            report.put("retracted_head",level.getBlockState(base.relative(direction)).toString());report.put("retraction_geometry_ack",revision);
            report.put("machines",PistonMachines.stats());report.put("passenger_acquisitions",BoatPassengers.stats().boarded()-passengersBefore.boarded());
            report.put("passenger_releases",BoatPassengers.stats().released()-passengersBefore.released());
        }
        log(extended?"extended":"retracted");return true;
    }
    Map<String,Object> report(){return Map.copyOf(report);}
    void fault(String scenario,long pausedMs){report.put("fault",scenario);report.put("observed_pause_ms",pausedMs);report.put("machines_after_fault",PistonMachines.stats());}
    void interrupt(){
        require(!interrupted&&PistonMachines.stats().pending()>0,"interrupt one prepared live flight");interrupted=true;stable=0;
        report.put("interrupted_geometry",WorldMotionWorld.current().geometry().revision());
        power(false);
        for(var at:List.of(base,referenceBase)){
            var head=at.relative(direction);
            // These cells were recorded before this fixture created either BE.
            level.removeBlockEntity(head);level.setBlockAndUpdate(head,Blocks.AIR.defaultBlockState());
            put.accept(at,Blocks.AIR.defaultBlockState());
        }
        log("source removed while prepared");
    }
    boolean cancelled(){
        if(!interrupted||PistonMachines.stats().pending()!=0||PistonMachines.machineActive(key)||!BoatPassengers.seated(key)||!acknowledged()){stable=0;return false;}
        if(++stable<5)return false;
        require(PistonMachines.stats().cancelled()>machinesBefore.cancelled(),"removed source cancels its prepared flight");
        require(PistonMachines.stats().cancelRestored()>machinesBefore.cancelRestored()&&PistonMachines.stats().cancelRestoreFailed()==machinesBefore.cancelRestoreFailed(),"cancel receipt restores all original ownership parts");
        for(var at:List.of(base,referenceBase))require((level.getBlockState(at).isAir()||level.getBlockState(at).is(Blocks.WATER))&&!(level.getBlockEntity(at) instanceof PistonMovingBlockEntity)&&!(level.getBlockEntity(at.relative(direction)) instanceof PistonMovingBlockEntity)&&
            !level.getBlockState(at.relative(direction)).is(Blocks.PISTON_HEAD),"removed source leaves no head or moving collision");
        require(EnemyMirrors.hitAttacker(key.id(),key.spawn()).getVehicle()==boat,"cancellation preserves actual MC seat");
        report.put("scenario","cancel_prepared_source");report.put("cancellation_geometry_ack",WorldMotionWorld.current().geometry().revision());report.put("machines",PistonMachines.stats());
        log("cancelled and passenger lease resumed");return true;
    }
    private String state(BlockPos at){
        var be=level.getBlockEntity(at);return level.getBlockState(at)+(be instanceof PistonMovingBlockEntity p?" progress="+p.getProgress(1)+" extending="+p.isExtending():"");
    }
    String diagnostic(){
        var view=WorldMotionWorld.current();var proxy=EnemyMirrors.hitAttacker(key.id(),key.spawn());
        return "base="+state(base)+",head="+state(base.relative(direction))+",reference_base="+state(referenceBase)+",reference_head="+state(referenceBase.relative(direction))+
            ",boat_delta="+boat.position().subtract(boatStart)+",native_delta="+(proxy==null?null:proxy.position().subtract(nativeStart))+
            ",reference_delta="+reference.position().subtract(referenceStart)+",seats="+boat.getPassengers().size()+",passenger="+BoatPassengers.diagnostic(key)+
            ",machine="+PistonMachines.diagnostic(key)+",stats="+PistonMachines.stats()+",geometry="+(view==null?null:view.geometry())+
            ",index="+WorldBlocks.index().revision()+",motion="+ShapeChanges.revision()+",ack="+acknowledged()+",broker_failure="+(view==null?null:view.requests().lastFailure());
    }
    void log(String reason){org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-piston-test] {} {}",reason,diagnostic());}
    void close(){for(var rider:riders)rider.discard();reference.discard();}
}
