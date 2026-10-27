package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/** Synchronous original dispenser ticks; ordinary scheduled fluid progression is checked by the caller. */
final class DispenserFluidCases {
    private record Spec(Item input,Item output,BlockState before,BlockState after){}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("dispenser fluid: "+why);}
    private static void originalTick(ServerLevel level,BlockPos p){level.getBlockState(p).tick(level,p,level.getRandom());}
    private static DispenserBlockEntity create(ServerLevel level,BlockPos source,Direction face,Item input){
        level.removeBlockEntity(source);level.setBlock(source,Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,face),2);
        var container=(DispenserBlockEntity)level.getBlockEntity(source);container.clearContent();container.setItem(0,new ItemStack(input));return container;
    }
    private static List<Entity> entities(ServerLevel level,BlockPos p){return level.getEntities((Entity)null,new AABB(p).inflate(3),e->true);}
    static void run(ServerLevel level){
        require(Boolean.getBoolean("sifucraft.fluidSceneSelfTest"),"explicit isolated fluid fixture");
        var source=new BlockPos(12,70,12);var target=source.east();var air=Blocks.AIR.defaultBlockState();
        require(level.isPositionEntityTicking(source),"spawn validation uses an entity-ticking chunk");
        var water=Blocks.WATER.defaultBlockState();var lava=Blocks.LAVA.defaultBlockState();var snow=Blocks.POWDER_SNOW.defaultBlockState();
        var wetSlab=Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true);var drySlab=wetSlab.setValue(BlockStateProperties.WATERLOGGED,false);
        var specs=List.of(new Spec(Items.WATER_BUCKET,Items.BUCKET,air,water),new Spec(Items.LAVA_BUCKET,Items.BUCKET,air,lava),new Spec(Items.POWDER_SNOW_BUCKET,Items.BUCKET,air,snow),new Spec(Items.COD_BUCKET,Items.BUCKET,air,water),new Spec(Items.BUCKET,Items.WATER_BUCKET,water,air),new Spec(Items.BUCKET,Items.LAVA_BUCKET,lava,air),new Spec(Items.BUCKET,Items.POWDER_SNOW_BUCKET,snow,air),new Spec(Items.BUCKET,Items.WATER_BUCKET,wetSlab,drySlab));
        var originals=new LinkedHashMap<BlockPos,BlockState>();var spawned=new ArrayList<Entity>();
        try{
            for(var p:List.of(source,target,new BlockPos(2,64,4),new BlockPos(3,64,4),new BlockPos(6,64,4),new BlockPos(5,64,4))){require(!level.getBlockState(p).hasBlockEntity(),"no saved BE replaced");originals.put(p,level.getBlockState(p));}
            require(SceneFluidGeometry.canDispenseTo(level,source,target),"open Native target");
            var initial=entities(level,source);var active=SifuCollision.active();var foreign=new SifuCollision.Active(active.grid(),new MapPlacement("Foreign",-4096,64,0));
            for(var spec:specs){
                level.setBlock(target,air,2);level.setBlock(target,spec.before,2);var container=create(level,source,Direction.EAST,spec.input);
                long seed=7193;level.getRandom().setSeed(seed);long unchanged=level.getRandom().nextLong();level.getRandom().setSeed(seed);
                SceneFluidGeometry.withUnavailableGeometry(()->{originalTick(level,source);return null;});
                LoggerFactory.getLogger("sifucraft").info("[dispenser-fluid-test] pending input={} remaining={} target={} queued={}",spec.input,container.getItem(0),level.getBlockState(target),level.getBlockTicks().hasScheduledTick(source,Blocks.DISPENSER));
                require(level.getRandom().nextLong()==unchanged,"pending geometry does not select a random slot using "+level.getRandom().getClass().getSimpleName());
                require(container.getItem(0).is(spec.input)&&container.getItem(0).getCount()==1&&level.getBlockState(target)==spec.before&&entities(level,source).equals(initial),"pending source/inventory/entities unchanged "+spec.input);
                require(level.getBlockTicks().hasScheduledTick(source,Blocks.DISPENSER),"pending bucket action scheduled once");
                level.getBlockTicks().clearArea(new BoundingBox(source));
                level.getRandom().setSeed(seed);
                SceneBlockSupport.withScene(foreign,()->{originalTick(level,source);return null;});
                require(level.getRandom().nextLong()==unchanged,"foreign bucket action does not advance random slot selection");
                require(container.getItem(0).is(spec.input)&&level.getBlockState(target)==spec.before,"inactive map does not dispense "+spec.input);
                require(level.getBlockTicks().hasScheduledTick(source,Blocks.DISPENSER),"foreign action postponed");level.getBlockTicks().clearArea(new BoundingBox(source));
                originalTick(level,source);require(container.getItem(0).is(spec.output)&&container.getItem(0).getCount()==1&&level.getBlockState(target)==spec.after,"original bucket conversion after readiness "+spec.input);
                var added=entities(level,source).stream().filter(e->!initial.contains(e)).toList();
                LoggerFactory.getLogger("sifucraft").info("[dispenser-fluid-test] resumed input={} output={} spawned={}",spec.input,container.getItem(0),added.stream().map(Entity::getType).toList());
                require(spec.input==Items.COD_BUCKET?added.size()==1&&added.getFirst().getType()==net.minecraft.world.entity.EntityTypes.COD:added.isEmpty(),"one original fish only on successful mob bucket");
                for(var e:added){spawned.add(e);e.discard();}
            }
            // The pending-geometry guard belongs to container buckets, not every machine output.
            level.setBlock(target,air,2);var arrows=create(level,source,Direction.EAST,Items.ARROW);var beforeArrow=entities(level,source);
            SceneFluidGeometry.withUnavailableGeometry(()->{originalTick(level,source);return null;});
            var arrowOutput=entities(level,source).stream().filter(e->!beforeArrow.contains(e)).toList();
            require(arrows.getItem(0).isEmpty()&&arrowOutput.size()==1&&arrowOutput.getFirst().getType()==net.minecraft.world.entity.EntityTypes.ARROW,"nonbucket projectile output remains original while fluid geometry is pending");
            for(var e:arrowOutput){spawned.add(e);e.discard();}
            for(var wall:List.of(new BlockPos(2,64,4),new BlockPos(6,64,4))){
                var machine=wall.getX()==2?wall.east():wall.west();var face=wall.getX()==2?Direction.WEST:Direction.EAST;
                require(!SceneFluidGeometry.canDispenseTo(level,machine,wall),"closed Native backing or gate face");
                for(var input:List.of(Items.BUCKET,Items.WATER_BUCKET)){
                    level.setBlock(wall,air,2);level.setBlock(wall,input==Items.BUCKET?water:air,2);var before=level.getBlockState(wall);var container=create(level,machine,face,input);var previous=entities(level,machine);originalTick(level,machine);
                    require(level.getBlockState(wall)==before,"bucket cannot affect source behind Native wall");
                    var added=entities(level,machine).stream().filter(e->!previous.contains(e)).toList();int total=container.getItem(0).is(input)?container.getItem(0).getCount():0;
                    for(var e:added){require(e instanceof ItemEntity&&((ItemEntity)e).getItem().is(input),"vanilla blocked bucket fallback preserves item type");total+=((ItemEntity)e).getItem().getCount();spawned.add(e);e.discard();}
                    require(total==1,"blocked bucket inventory plus emitted item conserved");
                }level.removeBlockEntity(machine);level.setBlock(machine,air,2);
            }
            LoggerFactory.getLogger("sifucraft").info("[dispenser-fluid-test] PASS 8 pending/foreign/resumed bucket conversions, waterlogged/powder/mob outputs, 4 Native wall fallbacks conserved, nonbucket arrow unchanged");
        }finally{
            for(var e:spawned)if(!e.isRemoved())e.discard();for(var p:originals.keySet()){level.getBlockTicks().clearArea(new BoundingBox(p));level.removeBlockEntity(p);}for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);
        }
    }
}
