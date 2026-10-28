package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Actual original scheduled bubble updates, including moving the base with a powered sticky piston. */
final class BubbleStructureCheck {
    private record Column(BlockPos base,int height,boolean down){int middle(){return Math.max(1,(height+1)/2);}}
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final List<Column> columns=new ArrayList<>();
    private final List<ItemEntity> items=new ArrayList<>();private final List<Map<String,Object>> rows=new ArrayList<>();private final Set<ChunkPos> forced=new HashSet<>();
    private volatile SifuFrame frame;private volatile boolean finished;private ServerLevel level;private int stage;private long began=System.currentTimeMillis(),phaseAt;private float hp;
    BubbleStructureCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-bubble-structure-check-"),"disposable bridge world");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("bubble structure: "+why);}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished)mc.stop();}
    private void put(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity()||originals.containsKey(pos),"no original BE replaced");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private BlockState base(boolean down){return (down?Blocks.MAGMA_BLOCK:Blocks.SOUL_SAND).defaultBlockState();}
    private void checkColumn(Column c,boolean down){for(int y=1;y<=c.height;y++){var at=c.base.above(y);var s=level.getBlockState(at);require(s.is(Blocks.BUBBLE_COLUMN)&&s.getValue(BubbleColumnBlock.DRAG_DOWN)==down,"complete original column at "+at+" got="+s+" expected_down="+down);}}
    private void noBubbles(Column c,int from){for(int y=from;y<=c.height;y++)require(!level.getBlockState(c.base.above(y)).is(Blocks.BUBBLE_COLUMN),"bubble propagation stops at interrupted source/base");}
    private void record(String name){var states=new ArrayList<Map<String,Object>>();for(var c:columns){var actual=new ArrayList<String>();for(int y=0;y<=c.height+1;y++)actual.add(dev.sifucraft.blocks.BlockStateKey.encode(level.getBlockState(c.base.above(y))));states.add(Map.of("height",c.height,"down",c.down,"states",actual));}rows.add(Map.of("case",name,"columns",states));org.slf4j.LoggerFactory.getLogger("sifucraft").info("[bubble-structure-test] completed case={} columns={}",name,columns.size());}
    private void next(int value){stage=value;phaseAt=level.getGameTime();}
    void tick(MinecraftServer server){if(finished)return;try{
        require(System.currentTimeMillis()-began<240000,"bounded original structure matrix");var v=WorldMotionWorld.current();if(v==null||v.nativeStatus()==null||v.nativeStatus().flags()!=0||!v.shapesCurrent()||frame==null||!frame.hasPawn()||v.placement().map().equals("MotionFixture"))return;
        level=server.overworld();
        if(stage==0){var p=frame.playerLocation();var origin=BlockPos.containing(v.placement().mcX(p.x())+24,v.placement().originY()+64,v.placement().mcZ(p.y())+8);hp=frame.health();int index=0;
            for(int h:new int[]{1,4,16})for(boolean down:new boolean[]{false,true}){
                var b=origin.offset(index++*4,0,0);columns.add(new Column(b,h,down));var chunk=new ChunkPos(b.getX()>>4,b.getZ()>>4);if(level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);
                for(int x=0;x<=2;x++){put(b.offset(x,-1,0),Blocks.OBSIDIAN.defaultBlockState());put(b.offset(x,0,-1),Blocks.GLASS.defaultBlockState());put(b.offset(x,0,1),Blocks.GLASS.defaultBlockState());}
                put(b.east(3),Blocks.GLASS.defaultBlockState());put(b,base(down));
                for(int y=1;y<=h+2;y++){var cell=b.above(y);for(var d:Direction.Plane.HORIZONTAL)put(cell.relative(d),Blocks.GLASS.defaultBlockState());put(cell,y<=h?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState());}
                put(b.west(),Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST));put(b.west(2),Blocks.AIR.defaultBlockState());put(b.east(),Blocks.AIR.defaultBlockState());
            }next(1);return;
        }
        require(frame.health()==hp,"Native player HP remains unchanged");
        for(var c:columns){var volume=new AABB(c.base.offset(-2,-1,-1)).minmax(new AABB(c.base.offset(2,c.height+2,1)));if(!SceneFluidGeometry.knownVolume(level,volume))return;}
        if(level.getGameTime()-phaseAt<60)return;
        switch(stage){
            case 1->{for(var c:columns)checkColumn(c,c.down);record("original_1_4_16");for(var c:columns)put(c.base,base(!c.down));next(2);}
            case 2->{for(var c:columns)checkColumn(c,!c.down);record("swap_soul_sand_magma");for(var c:columns)put(c.base,Blocks.AIR.defaultBlockState());next(3);}
            case 3->{for(var c:columns)noBubbles(c,1);record("remove_base");for(var c:columns)put(c.base,base(c.down));next(4);}
            case 4->{for(var c:columns)checkColumn(c,c.down);record("restore_base");for(var c:columns)put(c.base.above(c.middle()),Blocks.AIR.defaultBlockState());next(5);}
            case 5->{for(var c:columns){noBubbles(c,c.middle());for(int y=1;y<c.middle();y++)require(level.getBlockState(c.base.above(y)).is(Blocks.BUBBLE_COLUMN),"lower segment retains valid base");}record("remove_middle_source");for(var c:columns)put(c.base.above(c.middle()),Blocks.WATER.defaultBlockState());next(6);}
            case 6->{for(var c:columns)checkColumn(c,c.down);record("refill_source");for(var c:columns){put(c.base.above(c.height+1),Blocks.GLASS.defaultBlockState());var p=Vec3.atCenterOf(c.base.above(c.height));var item=new ItemEntity(level,p.x,p.y,p.z,new ItemStack(Items.DIAMOND));item.setDeltaMovement(Vec3.ZERO);item.setNeverPickUp();require(level.addFreshEntity(item),"registered capped-column original item");items.add(item);}next(7);}
            case 7->{for(int i=0;i<columns.size();i++){var c=columns.get(i);checkColumn(c,c.down);var item=items.get(i);require(item.isAlive()&&item.getBoundingBox().maxY<=c.base.getY()+c.height+1+1e-5,"cap blocks original bubble-driven item");}record("solid_top_cap");for(var item:items)item.discard();items.clear();for(var c:columns){put(c.base.above(c.height+1),Blocks.AIR.defaultBlockState());put(c.base.west(2),Blocks.REDSTONE_BLOCK.defaultBlockState());}next(8);}
            case 8->{for(var c:columns){require(level.getBlockState(c.base).is(Blocks.PISTON_HEAD)&&level.getBlockState(c.base.east()).getBlock()==base(c.down).getBlock(),"original powered piston moves actual base");noBubbles(c,1);}record("piston_moves_base");for(var c:columns)put(c.base.west(2),Blocks.AIR.defaultBlockState());next(9);}
            case 9->{for(var c:columns){require(level.getBlockState(c.base).getBlock()==base(c.down).getBlock(),"original sticky retraction returns base");checkColumn(c,c.down);}record("sticky_returns_base");for(var c:columns){put(c.base,Blocks.STONE.defaultBlockState());for(int y=c.height;y>=1;y--)put(c.base.above(y),Blocks.AIR.defaultBlockState());}next(10);}
            case 10->{for(var c:columns){noBubbles(c,1);for(int y=1;y<=c.height;y++)require(level.getFluidState(c.base.above(y)).isEmpty(),"drained column retains air");}record("drain_to_air");restore(server);next(11);}
            case 11->{require(dev.sifucraft.blocks.WorldBlocks.index().snapshots().stream().flatMap(s->s.states().values().stream()).noneMatch(s->!s.getFluidState().isEmpty()),"no escaped or leftover liquid anywhere in the disposable world index");AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-bubble-structures.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"backend","real Sifu finite geometry, actual original block/fluid/piston/world ticks; registered items, not NPC motion","no_residual_fluid",true,"cases",rows),out));finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[bubble-structure-test] PASS 1/4/16 up/down swap/removal/refill/cap/piston/air, zero residual liquid and cleanup ACK");}
            default->throw new AssertionError("unexpected matrix stage");
        }
    }catch(Exception|AssertionError error){restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[bubble-structure-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}}
    void restore(MinecraftServer ignored){for(var e:items)e.discard();items.clear();if(level!=null){for(var p:originals.keySet())level.removeBlockEntity(p);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);for(var c:forced)level.setChunkForced(c.x(),c.z(),false);}originals.clear();forced.clear();}
}
