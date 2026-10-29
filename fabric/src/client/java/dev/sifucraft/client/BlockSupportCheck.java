package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import dev.sifucraft.blocks.BlockSectionIndex;
import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.collision.CollisionGrid;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.storage.AtomicFiles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Real vanilla placement/survival in a disposable world with thread-scoped collision fixtures. */
final class BlockSupportCheck {
    private final BlockPos base=new BlockPos(16,201,16);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final ArrayList<ItemEntity> drops=new ArrayList<>();
    private ServerLevel level;
    private boolean done;
    BlockSupportCheck() {
        if(!DevWorld.LEVEL_ID.startsWith("sifucraft-support-check-"))throw new IllegalStateException("support check requires disposable save");
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("scene support: "+why);}
    private void remember(BlockPos pos){originals.putIfAbsent(pos,level.getBlockState(pos));require(!level.getBlockState(pos).hasBlockEntity(),"fixture has no block entity");}
    private void clear(BlockPos pos){remember(pos);level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);}
    private SifuCollision.Active grid(MinecraftServer server,String name,boolean full,boolean edge,boolean ceiling) throws java.io.IOException {
        byte[] map="support-fixture".getBytes(StandardCharsets.UTF_8);
        var bytes=ByteBuffer.allocate(40+map.length+76*2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8);
        bytes.putFloat(0).putFloat(0).putFloat(0).putInt(map.length).put(map).putInt(ceiling?2:full||edge?1:0);
        if(full||edge||ceiling){
            bytes.putInt(base.getX()).putInt(base.getZ()).putInt(base.getY());
            for(int y=0;y<8;y++)bytes.putLong(edge?0x0101010101010101L:-1L);
        }
        if(ceiling){bytes.putInt(base.getX()).putInt(base.getZ()).putInt(base.getY()+2);for(int y=0;y<8;y++)bytes.putLong(-1L);}
        var path=server.getWorldPath(LevelResource.ROOT).resolve(name+".scvx");
        java.nio.file.Files.write(path,java.util.Arrays.copyOf(bytes.array(),bytes.position()));
        return new SifuCollision.Active(CollisionGrid.read(path),new MapPlacement("support-fixture",0,0,0));
    }
    private void place(net.minecraft.world.level.block.Block block,BlockPos pos,Direction face) {
        remember(pos);var stack=new ItemStack(block);
        require(stack.getItem() instanceof BlockItem,"registered block item");
        ((BlockItem)stack.getItem()).place(new DirectionalPlaceContext(level,pos,face.getOpposite(),stack,face));
        require(level.getBlockState(pos).is(block)&&stack.isEmpty(),"actual BlockItem placement and single item consumption: "+block+" "+face);
    }
    void tick(MinecraftServer server) {
        if(done||server.getPlayerList().getPlayers().isEmpty()||SifuCollision.active()==null||WorldBlocks.index()==null)return;
        if(server.getPlayerList().getPlayers().getFirst().getY()<SifuCollision.active().placement().originY())return;
        done=true;level=server.overworld();
        try {
            clear(base);for(var face:Direction.values())clear(base.relative(face));clear(base.above(2));clear(base.above(3));
            var full=grid(server,"full",true,false,false);var empty=grid(server,"empty",false,false,false);
            var edge=grid(server,"edge",false,true,false);var ceiling=grid(server,"ceiling",true,false,true);
            int[] placements={0};
            SceneBlockSupport.withScene(full,()->{
                require(level.getBlockState(base).isAir(),"Native support never writes a backing Minecraft block");
                for(var block:BuiltInRegistries.BLOCK)if(block instanceof ButtonBlock||block==Blocks.LEVER)for(var face:Direction.values()) {
                    var pos=base.relative(face);place(block,pos,face);var state=level.getBlockState(pos);
                    require(state.canSurvive(level,pos),"placed attachment survives on Native geometry");
                    require(!SceneBlockSupport.withScene(empty,()->state.canSurvive(level,pos)),"same vanilla attachment rejects missing Native support");
                    var player=server.getPlayerList().getPlayers().getFirst();
                    state.useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(pos),face,pos,false));
                    require(level.getBlockState(pos).getValue(BlockStateProperties.POWERED),"vanilla attached input responds");
                    level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);placements[0]++;
                }
                for(var block:new net.minecraft.world.level.block.Block[]{Blocks.REDSTONE_WIRE,Blocks.REPEATER,Blocks.COMPARATOR,Blocks.STONE_PRESSURE_PLATE}) {
                    place(block,base.above(),Direction.UP);level.setBlock(base.above(),Blocks.AIR.defaultBlockState(),2);placements[0]++;
                }
                place(Blocks.OAK_DOOR,base.above(),Direction.UP);
                require(level.getBlockState(base.above(2)).is(Blocks.OAK_DOOR),"Native floor supports both door halves");
                level.setBlock(base.above(),Blocks.AIR.defaultBlockState(),2);level.setBlock(base.above(2),Blocks.AIR.defaultBlockState(),2);
                return null;
            });
            SceneBlockSupport.withScene(edge,()->{
                require(!Blocks.AIR.defaultBlockState().isFaceSturdy(level,base,Direction.UP)&&
                    !Blocks.AIR.defaultBlockState().isFaceSturdy(level,base,Direction.EAST),"partial Native face never becomes a full supporting cube");
                require(!edge.solidAt(base.getX()+.5,base.getY()+.5,base.getZ()+.5),"edge fixture defeats old centre-only placement check");
                require(SceneBlockSupport.intersects(level,base,Blocks.STONE.defaultBlockState()),"full placed shape rejects a thin Native edge");
                var stack=new ItemStack(Blocks.STONE);((BlockItem)stack.getItem()).place(new DirectionalPlaceContext(level,base,Direction.DOWN,stack,Direction.UP));
                require(level.getBlockState(base).isAir()&&stack.getCount()==1,"rejected embedding consumes no block item");return null;
            });
            SceneBlockSupport.withScene(ceiling,()->{
                var stack=new ItemStack(Blocks.OAK_DOOR);((BlockItem)stack.getItem()).place(new DirectionalPlaceContext(level,base.above(),Direction.DOWN,stack,Direction.UP));
                require(level.getBlockState(base.above()).isAir()&&level.getBlockState(base.above(2)).isAir()&&stack.getCount()==1,"blocked upper door half rejects entire placement without consumption");return null;
            });
            // A Native door losing its support must drop one attachment, without fabricating blocks.
            SceneBlockSupport.withScene(full,()->{place(Blocks.STONE_BUTTON,base.north(),Direction.NORTH);return null;});
            var index=new BlockSectionIndex();index.changed(base.north(),level.getBlockState(base.north()));
            var updates=new SceneBlockSupport.Updates();updates.update(level,index,full);
            updates.update(level,index,new SifuCollision.Active(empty.grid(),new MapPlacement("other",MapSlots.SPACING,0,0)));
            updates.update(level,index,full);
            updates.update(level,index,new SifuCollision.Active(empty.grid(),empty.placement(),null,9,3));
            updates.update(level,index,null);
            require(level.getBlockState(base.north()).is(Blocks.STONE_BUTTON),"map change/disconnection never destroys saved attachments");
            updates.update(level,index,full);
            var otherSave=new BlockSectionIndex();otherSave.changed(base.north(),level.getBlockState(base.north()));
            updates.update(level,otherSave,empty);
            require(level.getBlockState(base.north()).is(Blocks.STONE_BUTTON),"new derived index/save cannot inherit pending support removal");
            updates.update(level,index,full);updates.update(level,index,empty);updates.update(level,index,empty);
            require(level.getBlockState(base.north()).isAir(),"changed Native support removes the unsupported attachment");
            drops.addAll(level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(2)));
            require(drops.stream().mapToInt(e->e.getItem().is(Blocks.STONE_BUTTON.asItem())?e.getItem().getCount():0).sum()==1,"support loss produces exactly one original item");
            require(!Blocks.AIR.defaultBlockState().isFaceSturdy(level,base,Direction.UP),"thread-scoped test support is released");
            SceneBlockSupport.withScene(full,()->{
                var nether=server.getLevel(Level.NETHER);
                require(nether.getBlockState(base).isAir()&&!nether.getBlockState(base).isFaceSturdy(nether,base,Direction.UP),"Native support cannot leak into another dimension");return null;
            });
            boolean realSurface=false;
            var player=server.getPlayerList().getPlayers().getFirst();var center=player.blockPosition();
            search:for(var support:BlockPos.betweenClosed(center.offset(-8,-3,-8),center.offset(8,3,8)))for(var face:Direction.values()) {
                var pos=support.relative(face);
                if(!level.getBlockState(support).isAir()||!level.getBlockState(pos).isAir()||
                    !level.getBlockState(support).isFaceSturdy(level,support,face))continue;
                var stack=new ItemStack(Blocks.STONE_BUTTON);var context=new DirectionalPlaceContext(level,pos,face.getOpposite(),stack,face);
                var state=Blocks.STONE_BUTTON.getStateForPlacement(context);
                if(state==null||SceneBlockSupport.intersects(level,pos,state))continue;
                place(Blocks.STONE_BUTTON,pos,face);require(level.getBlockState(pos).canSurvive(level,pos),"attachment on actual loaded Sifu grid survives");
                level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);realSurface=true;break search;
            }
            require(realSurface,"actual loaded Sifu grid provides a usable support face near the player");
            var report=Map.of("result","PASS","attachment_placements",placements[0],"door_halves",true,"edge_embedding_rejected",true,"upper_door_rejected",true,"support_loss_single_drop",true,
                "actual_sifu_grid_placement",true,"native_geometry_fixture","thread_scoped_not_real_door_motion");
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-support-check.json"),out->new GsonBuilder().setPrettyPrinting().create().toJson(report,out));
            restore(server);LoggerFactory.getLogger("sifucraft").info("[scene-support-test] PASS {} input/logic placements, Native support shape, door halves, shape overlap, upper obstruction and single-drop support loss",placements[0]);
            Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
        }catch(Exception|AssertionError e){restore(server);LoggerFactory.getLogger("sifucraft").error("[scene-support-test] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer server) {
        if(level==null)return;for(var drop:drops)drop.discard();drops.clear();
        for(var entry:originals.entrySet()){level.removeBlockEntity(entry.getKey());level.setBlock(entry.getKey(),entry.getValue(),2);}originals.clear();
    }
}
