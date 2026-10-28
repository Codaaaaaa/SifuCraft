package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionProtocol;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Real finite native coverage: known water, unknown-volume TNT retention and no MC feedback. */
final class CoverageSceneCheck {
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile SifuFrame frame;private volatile boolean finished;private ServerLevel level;
    private BlockPos cell,outside;private PrimedTnt waiting;private net.minecraft.world.entity.vehicle.boat.AbstractBoat waitingBoat;private long began,worldTick;private int stage;private float health;
    private final int holdTicks=Math.clamp(Integer.getInteger("sifucraft.coverageHoldTicks",0),0,600);
    CoverageSceneCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-coverage-check-"),"disposable save");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("coverage scene: "+why);}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished)mc.stop();}
    private void put(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"no original BE touched");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    void tick(MinecraftServer server){
        if(finished)return;if(began==0)began=System.currentTimeMillis();
        try{
            require(System.currentTimeMillis()-began<180000,"bounded query/coverage loading");var view=WorldMotionWorld.current();
            if(view==null||view.nativeStatus()==null||frame==null||!frame.hasPawn()||(view.nativeStatus().capabilities()&WorldMotionProtocol.COVERAGE_CAPABILITY)==0||view.scene()==null||!view.scene().grid().hasCoverage()||!view.shapesCurrent()||!SceneFluidGeometry.available(server.overworld()))return;
            level=server.overworld();var scene=view.scene();
            if(stage==0){
                var p=frame.playerLocation();var feet=BlockPos.containing(view.placement().mcX(p.x()),view.placement().mcY(p.z()-frame.capsuleHalfHeight()),view.placement().mcZ(p.y()));
                for(var d:Direction.Plane.HORIZONTAL){var candidate=feet.relative(d,2).above();if(level.hasChunkAt(candidate)&&scene.knownBlock(candidate)&&scene.shapeAt(candidate).isEmpty()&&scene.shapeAt(candidate.below()).isEmpty()&&level.getBlockState(candidate).isAir()){cell=candidate;break;}}
                require(cell!=null,"known, physically clear nearby water cell");
                for(int distance=64;distance<=192;distance+=32){var candidate=cell.offset(distance,16,0);if(level.hasChunkAt(candidate)&&!scene.knownBlock(candidate)){outside=candidate;break;}}
                require(outside!=null&&!SceneFluidGeometry.canOccupy(level,outside),"select a genuinely uncached region and refuse unknown water");
                var tnt=new PrimedTnt(EntityTypes.TNT,level);tnt.setPos(Vec3.atCenterOf(outside));tnt.setFuse(8);tnt.setDeltaMovement(.01,.02,.03);var start=tnt.position();var velocity=tnt.getDeltaMovement();
                for(int i=0;i<12;i++)level.tickNonPassenger(tnt);
                require(tnt.getFuse()==8&&tnt.position().equals(start)&&tnt.getDeltaMovement().equals(velocity),"unknown trajectory retains the complete original TNT tick");waiting=tnt;
                waitingBoat=(net.minecraft.world.entity.vehicle.boat.AbstractBoat)EntityTypes.OAK_BOAT.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                require(waitingBoat!=null,"original registry boat");waitingBoat.setPos(Vec3.atCenterOf(outside.above(4)));waitingBoat.setDeltaMovement(.01,.02,.03);var boatStart=waitingBoat.position();var boatVelocity=waitingBoat.getDeltaMovement();
                for(int i=0;i<12;i++)level.tickNonPassenger(waitingBoat);
                require(waitingBoat.position().equals(boatStart)&&waitingBoat.getDeltaMovement().equals(boatVelocity),"unknown boat trajectory retains pose and velocity");
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){put(outside.offset(x,-1,z),Blocks.OBSIDIAN.defaultBlockState());put(outside.offset(x,0,z),(Math.abs(x)==2||Math.abs(z)==2?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState());}
                put(outside,Blocks.WATER.defaultBlockState());
                put(cell.below(),Blocks.OBSIDIAN.defaultBlockState());for(var d:Direction.Plane.HORIZONTAL)put(cell.relative(d),Blocks.OBSIDIAN.defaultBlockState());put(cell,Blocks.WATER.defaultBlockState());
                health=frame.health();worldTick=level.getGameTime();stage=1;
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[coverage-scene-test] known water at {} unknown TNT retained for 12 ticks",cell);return;
            }
            require(frame.health()==health,"native player HP remains unchanged");
            if(stage==1){if(level.getGameTime()-worldTick<40)return;
                require(level.getFluidState(cell).isSource()&&SceneFluidGeometry.canOccupy(level,cell),"actual original water ticks in certified air");
                var center=Vec3.atCenterOf(cell.below());require(!scene.solidAt(center.x,center.y,center.z),"MC basin collision does not feed back into native geometry");
                if(!scene.knownBlock(outside)||level.getFluidState(outside.east()).isEmpty())return;
                require(level.getFluidState(outside).isSource(),"remote source preserved and original fluid ticks resume spreading after coverage");
                restore(server);stage=2;return;
            }
            if(stage==2){
                if(!SceneFluidGeometry.knownVolume(level,waiting.getBoundingBox().expandTowards(waiting.getDeltaMovement())))return;
                if(!SceneFluidGeometry.knownVolume(level,waitingBoat.getBoundingBox().expandTowards(waitingBoat.getDeltaMovement()).inflate(1)))return;
                require(scene.knownBlock(outside)&&SceneFluidGeometry.canOccupy(level,outside),"remote request publishes independently certified air");
                level.tickNonPassenger(waiting);require(waiting.getFuse()==7,"original TNT resumes exactly one tick after actual coverage");waiting.discard();waiting=null;
                var beforeBoat=waitingBoat.position();level.tickNonPassenger(waitingBoat);require(waitingBoat.position().distanceToSqr(beforeBoat)>0,"original boat tick resumes after coverage");waitingBoat.discard();waitingBoat=null;
                worldTick=level.getGameTime();stage=3;return;
            }
            if(stage==3){
                if(level.getGameTime()-worldTick<holdTicks)return;
                var residual=new java.util.ArrayList<Map<String,Object>>();
                for(var section:dev.sifucraft.blocks.WorldBlocks.index().snapshots())for(var entry:section.states().entrySet())if(!entry.getValue().getFluidState().isEmpty()){
                    var s=net.minecraft.core.SectionPos.of(section.section());short key=entry.getKey();var pos=new BlockPos(s.minBlockX()+net.minecraft.core.SectionPos.sectionRelativeX(key),s.minBlockY()+net.minecraft.core.SectionPos.sectionRelativeY(key),s.minBlockZ()+net.minecraft.core.SectionPos.sectionRelativeZ(key));
                    residual.add(Map.of("pos",pos.toString(),"indexed",entry.getValue().toString(),"actual",level.getBlockState(pos).toString()));
                }
                if(!residual.isEmpty())AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-residual-fluid.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(residual,out));
                require(residual.isEmpty(),"zero residual liquid after both finite basins are restored: "+residual.stream().limit(6).toList());
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-coverage-scene.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"backend","real Sifu finite physics query and SCVXv2","unknown_tnt_ticks_retained",12,"remote_tnt_resumed",true,"unknown_boat_ticks_retained",12,"remote_boat_resumed",true,"remote_water_resumed",true,"known_water_ticks",40,"native_hp",health),out));
                finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[coverage-scene-test] PASS known air/water, unknown TNT/boat retention, remote query and original TNT/boat/water resume, MC exclusion and cleanup");
            }
        }catch(Exception|AssertionError error){restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[coverage-scene-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer server){if(level!=null){
        // Capture every interior cell before spreading, and remove liquid before
        // its supports so teardown cannot create a falling-water column.
        for(var pos:originals.keySet())if(!level.getFluidState(pos).isEmpty())level.setBlock(pos,Blocks.AIR.defaultBlockState(),18);
        for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),18);originals.clear();
    }}
}
