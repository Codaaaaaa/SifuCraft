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
    private BlockPos cell;private long began,worldTick;private int stage;private float health;
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
                var outside=cell.offset(64,16,0);require(!scene.knownBlock(outside)&&!SceneFluidGeometry.canOccupy(level,outside),"unknown region cannot accept water");
                var tnt=new PrimedTnt(EntityTypes.TNT,level);tnt.setPos(Vec3.atCenterOf(outside));tnt.setFuse(8);tnt.setDeltaMovement(.01,.02,.03);var start=tnt.position();var velocity=tnt.getDeltaMovement();
                for(int i=0;i<12;i++)level.tickNonPassenger(tnt);
                require(tnt.getFuse()==8&&tnt.position().equals(start)&&tnt.getDeltaMovement().equals(velocity),"unknown trajectory retains the complete original TNT tick");tnt.discard();
                put(cell.below(),Blocks.OBSIDIAN.defaultBlockState());for(var d:Direction.Plane.HORIZONTAL)put(cell.relative(d),Blocks.OBSIDIAN.defaultBlockState());put(cell,Blocks.WATER.defaultBlockState());
                health=frame.health();worldTick=level.getGameTime();stage=1;
                org.slf4j.LoggerFactory.getLogger("sifucraft").info("[coverage-scene-test] known water at {} unknown TNT retained for 12 ticks",cell);return;
            }
            require(frame.health()==health,"native player HP remains unchanged");
            if(stage==1){if(level.getGameTime()-worldTick<40)return;
                require(level.getFluidState(cell).isSource()&&SceneFluidGeometry.canOccupy(level,cell),"actual original water ticks in certified air");
                var center=Vec3.atCenterOf(cell.below());require(!scene.solidAt(center.x,center.y,center.z),"MC basin collision does not feed back into native geometry");
                restore(server);stage=2;return;
            }
            if(stage==2){
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-coverage-scene.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"backend","real Sifu finite physics query and SCVXv2","unknown_tnt_ticks_retained",12,"known_water_ticks",40,"native_hp",health),out));
                finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[coverage-scene-test] PASS finite known air, unknown fluid refusal/TNT retention, actual water, MC exclusion and cleanup");
            }
        }catch(Exception|AssertionError error){restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[coverage-scene-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer server){if(level!=null){for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}}
}
