package dev.sifucraft.client;

import dev.sifucraft.storage.AtomicFiles;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Server-owned fixture outside the original scene, with exact Native revision ACK after every change. */
final class ShapeUpdateCheck {
    private ServerLevel level;private BlockPos pos;private BlockState original;
    private volatile boolean ready,waiting,finished;
    private int step,acks;private long deadline;
    private final List<BlockState> states=List.of(
        Blocks.STONE_SLAB.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,SlabType.TOP),
        Blocks.STONE.defaultBlockState(),Blocks.OAK_TRAPDOOR.defaultBlockState(),
        Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN,true).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST),
        Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN,true).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH),
        Blocks.OAK_DOOR.defaultBlockState(),Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN,true),
        Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState());
    ShapeUpdateCheck(){if(!DevWorld.LEVEL_ID.startsWith("sifucraft-shape-update-check-"))throw new IllegalStateException("disposable shape update save required");}
    void clientTick(Minecraft mc,boolean available){
        if(finished){mc.stop();return;}
        ready=available&&SifuCraftClient.blockCollisionCurrent();
        if(waiting&&SifuCraftClient.blockCollisionCurrent()){acks++;waiting=false;}
    }
    void tick(MinecraftServer server){
        if(finished||!ready)return;
        if(level==null){level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();pos=player.blockPosition().atY(201);original=level.getBlockState(pos);
            if(original.hasBlockEntity())throw new AssertionError("shape fixture contains saved BE");deadline=System.currentTimeMillis()+30000;}
        if(System.currentTimeMillis()>deadline)throw new AssertionError("Native shape revision acknowledgement deadline step="+step);
        if(waiting)return;
        if(step==states.size()){
            if(acks!=states.size())throw new AssertionError("missing exact shape ACK");
            restore(server);
            try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-shape-update.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                Map.of("result","PASS","steps",step,"native_acks",acks,"player_teleported",false,"fixture_restored",true),out));}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
            finished=true;LoggerFactory.getLogger("sifucraft").info("[shape-update-test] PASS {} scoped Native collision revision ACKs; player pose untouched",acks);return;
        }
        level.setBlock(pos,states.get(step++),2);waiting=true;
    }
    void restore(MinecraftServer ignored){if(level!=null&&original!=null){level.setBlock(pos,original,2);original=null;}}
}
