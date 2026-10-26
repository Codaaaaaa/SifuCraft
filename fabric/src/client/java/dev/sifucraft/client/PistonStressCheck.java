package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Real vanilla updates and moving block entities; each cycle waits for actual Native collision ACK. */
final class PistonStressCheck {
    private static PistonStressCheck current;
    private final Map<BlockPos,BlockState> originals = new LinkedHashMap<>();
    private volatile BlockPos base;
    private volatile boolean waiting, restored, failed;
    private volatile int cycles, acknowledgements;
    private volatile long movingRendered;
    private ServerLevel level;
    private BlockPos sticky, button, lamp, door;
    private int step, ticks, movingObserved;
    private boolean pressed, released, lit, wirePowered, doorOpened, doorClosed;
    private boolean done;
    private long deadline, restoredAt;
    PistonStressCheck() {
        if (!DevWorld.LEVEL_ID.startsWith("sifucraft-piston-check-")) throw new IllegalStateException("piston check requires disposable save");
        if (!BlockShapes.ENABLED || !WorldBlocks.ENABLED) throw new IllegalStateException("piston check requires P8 shapes/index");
        current = this;
    }
    private static void require(boolean ok,String why) { if (!ok) throw new AssertionError("piston stress: "+why); }
    private void set(BlockPos pos,BlockState state) {
        originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);
    }
    private void prepare(MinecraftServer server) {
        level=server.overworld();sticky=base.south(3);button=base.south(6);lamp=button.west(5);door=button.south(3);
        for(var at:BlockPos.betweenClosed(base.offset(-6,-1,-1),base.offset(4,2,10))) {
            require(!level.getBlockState(at).hasBlockEntity(),"fixture region has no block entity");
            originals.put(at.immutable(),level.getBlockState(at));
        }
        for(var at:originals.keySet())level.setBlock(at,Blocks.AIR.defaultBlockState(),2);
        set(base,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.EAST));
        set(sticky,Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.EAST));
        set(base.east(),Blocks.STONE.defaultBlockState());set(sticky.east(),Blocks.STONE.defaultBlockState());
        for(int x=0;x<=5;x++)set(button.west(x).below(),Blocks.STONE.defaultBlockState());
        set(button,Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,AttachFace.FLOOR));
        for(int x=1;x<=4;x++)set(button.west(x),Blocks.REDSTONE_WIRE.defaultBlockState());
        set(lamp,Blocks.REDSTONE_LAMP.defaultBlockState());set(door.below(),Blocks.STONE.defaultBlockState());
        set(door,Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER));
        set(door.above(),Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
        var player=server.getPlayerList().getPlayers().getFirst();
        level.getBlockState(button).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(button),Direction.UP,button,false));
        level.getBlockState(door).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(door),Direction.NORTH,door,false));
        pressed=level.getBlockState(button).getValue(BlockStateProperties.POWERED);
        doorOpened=level.getBlockState(door).getValue(BlockStateProperties.OPEN)&&level.getBlockState(door.above()).getValue(BlockStateProperties.OPEN);
        require(pressed&&doorOpened,"actual vanilla button and both door halves respond");
    }
    void serverTick(MinecraftServer server) {
        if(failed||restored||base==null)return;
        try {
            if(level==null)prepare(server);
            ticks++;
            for(var at:new BlockPos[]{base,base.east(),base.east(2),sticky,sticky.east(),sticky.east(2),button,lamp,door,door.above()}) {
                var actual=level.getBlockState(at);var section=WorldBlocks.index().snapshot(SectionPos.of(at).asLong());
                var indexed=section==null?null:section.states().get(SectionPos.sectionRelativePos(at));
                require(actual.isAir()?indexed==null:indexed==actual,"derived state equals vanilla at "+at);
                if(level.getBlockEntity(at) instanceof PistonMovingBlockEntity)movingObserved++;
            }
            lit|=level.getBlockState(lamp).getValue(BlockStateProperties.LIT);
            wirePowered|=level.getBlockState(button.west()).getValue(BlockStateProperties.POWER)>0;
            if(!level.getBlockState(button).getValue(BlockStateProperties.POWERED))released=true;
            if(ticks==30){
                var player=server.getPlayerList().getPlayers().getFirst();
                level.getBlockState(door).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(door),Direction.NORTH,door,false));
                doorClosed=!level.getBlockState(door).getValue(BlockStateProperties.OPEN)&&!level.getBlockState(door.above()).getValue(BlockStateProperties.OPEN);
            }
            if(waiting)return;
            if(cycles==100){
                require(acknowledgements==100&&movingObserved>0&&movingRendered>0,"100 native ACKs and server/client moving piston states");
                require(pressed&&released&&lit&&wirePowered&&doorOpened&&doorClosed,"button/wire/lamp and door lifecycle");
                require(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT)&&level.getBlockState(button.west()).getValue(BlockStateProperties.POWER)==0,"real scheduled button release clears circuit");
                var report=Map.of("result","PASS","cycles",cycles,"native_acked_cycles",acknowledgements,"ticks",ticks,
                    "moving_server_samples",movingObserved,"moving_render_states",movingRendered,"button_wire_lamp",true,"door_both_halves",true);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-piston-stress.json"),out->new GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                restore(server);restoredAt=System.currentTimeMillis();restored=true;return;
            }
            if(step==0){
                if(cycles>0){set(base.east(2),Blocks.AIR.defaultBlockState());set(base.east(),Blocks.STONE.defaultBlockState());}
                set(base.west(),Blocks.REDSTONE_BLOCK.defaultBlockState());set(sticky.west(),Blocks.REDSTONE_BLOCK.defaultBlockState());
            }
            if(step==3)for(var p:new BlockPos[]{base,sticky}) {
                require(level.getBlockState(p).getValue(BlockStateProperties.EXTENDED),"piston extended");
                require(level.getBlockState(p.east(2)).is(Blocks.STONE),"vanilla stone pushed");
            }
            if(step==6){set(base.west(),Blocks.AIR.defaultBlockState());set(sticky.west(),Blocks.AIR.defaultBlockState());}
            if(step==10){
                for(var p:new BlockPos[]{base,sticky})require(!level.getBlockState(p).getValue(BlockStateProperties.EXTENDED),"piston retracted");
                require(level.getBlockState(base.east()).isAir()&&level.getBlockState(base.east(2)).is(Blocks.STONE),"normal piston leaves stone");
                require(level.getBlockState(sticky.east()).is(Blocks.STONE)&&level.getBlockState(sticky.east(2)).isAir(),"sticky piston pulls stone");
                cycles++;waiting=true;step=0;
                if(cycles%25==0)LoggerFactory.getLogger("sifucraft").info("[piston-stress] cycles={} moving_server={} moving_render={}",cycles,movingObserved,movingRendered);
                return;
            }
            step++;
        }catch(Exception|AssertionError e){failed=true;LoggerFactory.getLogger("sifucraft").error("[piston-stress] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void clientTick(Minecraft mc,boolean guiReady) {
        if(done||failed||!guiReady||mc.player==null)return;
        if(base==null){deadline=System.currentTimeMillis()+180000;base=BlockPos.containing(mc.player.position().add(mc.player.getLookAngle().scale(3))).above();return;}
        require(System.currentTimeMillis()<deadline,"deadline cycles="+cycles+" native="+acknowledgements);
        if(waiting&&SifuCraftClient.blockCollisionCurrent()){acknowledgements++;waiting=false;}
        if(restored&&System.currentTimeMillis()-restoredAt>1000&&SifuCraftClient.blockCollisionCurrent()){
            done=true;LoggerFactory.getLogger("sifucraft").info("[piston-stress] PASS 100 normal/sticky cycles with actual Native ACK, moving block renderer, vanilla button/wire/lamp and door interaction; fixture restored");mc.stop();
        }
    }
    static void observe(LevelRenderState state){var c=current;if(c!=null&&!c.done&&!c.restored)for(var entity:state.blockEntityRenderStates)if(entity instanceof PistonHeadRenderState)c.movingRendered++;}
    void restore(MinecraftServer server){if(level==null)return;for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),2);originals.clear();}
}
