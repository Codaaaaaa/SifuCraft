package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import dev.sifucraft.blocks.PistonSceneGuard;
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
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/** Actual vanilla resolver/event checks; Native geometry is scoped to the server call stack only. */
final class PistonSceneCheck {
    private record Cell(BlockPos pos,int minX,int minY,int minZ,int maxX,int maxY,int maxZ){}
    private final BlockPos base=new BlockPos(16,201,16);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private ServerLevel level;
    private boolean done;
    private int sequence,blockedAttempts;
    private final boolean needNative=Boolean.getBoolean("sifucraft.signalNativeTest");
    private volatile boolean ready,finished;
    private volatile long restoredAt;
    private boolean clientDone;
    PistonSceneCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-piston-scene-check-"),"disposable save required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston scene: "+why);}
    void clientTick(Minecraft mc,boolean guiReady){
        if(clientDone)return;ready=guiReady&&SifuCraftClient.blockCollisionCurrent();
        if(!finished)return;
        require(System.currentTimeMillis()-restoredAt<30000,"Native cleanup revision ACK deadline");
        if(System.currentTimeMillis()-restoredAt<1000||!SifuCraftClient.blockCollisionCurrent())return;
        write(mc.getSingleplayerServer(),"p8-piston-scene-native-ack.json",Map.of("result","PASS","fixture_restored",true,"native_revision_applied",true));
        clientDone=true;mc.stop();
    }
    private static void write(MinecraftServer server,String name,Object value){
        try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve(name),out->new GsonBuilder().setPrettyPrinting().create().toJson(value,out));}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private void set(BlockPos pos,BlockState state){
        originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));require(!originals.get(pos).hasBlockEntity(),"fixture has no original BE");
        level.setBlock(pos,state,2);
    }
    private void clear(){for(var pos:originals.keySet()){level.removeBlockEntity(pos);level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);}}
    private void piston(Direction face,boolean sticky,boolean extended){
        clear();set(base,(sticky?Blocks.STICKY_PISTON:Blocks.PISTON).defaultBlockState().setValue(BlockStateProperties.FACING,face).setValue(BlockStateProperties.EXTENDED,extended));
    }
    private static Cell full(BlockPos pos){return new Cell(pos,0,0,0,8,8,8);}
    private SifuCollision.Active grid(MinecraftServer server,Cell... boxes) throws java.io.IOException {
        var cells=new LinkedHashMap<BlockPos,long[]>();
        for(var box:boxes){var bits=cells.computeIfAbsent(box.pos,p->new long[8]);
            for(int y=box.minY;y<box.maxY;y++)for(int z=box.minZ;z<box.maxZ;z++)for(int x=box.minX;x<box.maxX;x++)bits[y]|=1L<<(z*8+x);}
        byte[] map="piston-scene-fixture".getBytes(StandardCharsets.UTF_8);
        var bytes=ByteBuffer.allocate(40+map.length+76*cells.size()).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8).putFloat(0).putFloat(0).putFloat(0).putInt(map.length).put(map).putInt(cells.size());
        for(var entry:cells.entrySet()){var pos=entry.getKey();bytes.putInt(pos.getX()).putInt(pos.getZ()).putInt(pos.getY());for(long bits:entry.getValue())bytes.putLong(bits);}
        var path=server.getWorldPath(LevelResource.ROOT).resolve("piston-scene-"+(sequence++)+".scvx");
        java.nio.file.Files.write(path,java.util.Arrays.copyOf(bytes.array(),bytes.position()));
        return new SifuCollision.Active(CollisionGrid.read(path),new MapPlacement("piston-scene-fixture",0,0,0));
    }
    private PistonStructureResolver query(String name,Direction face,boolean extending,SifuCollision.Active scene,boolean expected){
        var before=new LinkedHashMap<BlockPos,BlockState>();for(var pos:originals.keySet())before.put(pos,level.getBlockState(pos));
        var index=WorldBlocks.index();long revision=index==null?-1:index.revision();
        var resolver=new PistonStructureResolver(level,base,face,extending);
        boolean actual=SceneBlockSupport.withScene(scene,resolver::resolve);
        require(actual==expected,name+"/"+face+" resolved="+actual);
        for(var entry:before.entrySet())require(level.getBlockState(entry.getKey())==entry.getValue(),"resolver never mutates blocks: "+name);
        require(index==null||index.revision()==revision,"resolver leaves derived revision unchanged");
        rows.add(Map.of("case",name,"direction",face.getName(),"extending",extending,"resolved",actual,"pushed",resolver.getToPush().size(),"destroyed",resolver.getToDestroy().size()));
        return resolver;
    }
    private void finishMoving(){
        for(int pass=0;pass<5;pass++)for(var pos:new ArrayList<>(originals.keySet()))if(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving)
            PistonMovingBlockEntity.tick(level,pos,level.getBlockState(pos),moving);
        for(var pos:originals.keySet())require(!(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity),"all actual moving BEs finish");
    }
    private void rememberMovement(Direction face){
        for(int i=1;i<=3;i++)set(base.relative(face,i),Blocks.AIR.defaultBlockState());
        set(base.relative(face.getOpposite()),Blocks.AIR.defaultBlockState());
    }
    private void eventCases(MinecraftServer server,SifuCollision.Active empty) throws java.io.IOException {
        for(var face:Direction.values())for(boolean sticky:new boolean[]{false,true}){
            piston(face,sticky,false);rememberMovement(face);set(base.relative(face),Blocks.STONE.defaultBlockState());
            set(base.relative(face.getOpposite()),Blocks.REDSTONE_BLOCK.defaultBlockState());
            boolean accepted=SceneBlockSupport.withScene(empty,()->level.getBlockState(base).triggerEvent(level,base,PistonBaseBlock.TRIGGER_EXTEND,face.get3DDataValue()));
            require(accepted,"actual clear extension accepted");finishMoving();
            require(level.getBlockState(base).getValue(BlockStateProperties.EXTENDED)&&level.getBlockState(base.relative(face,2)).is(Blocks.STONE),"actual payload reached destination");
            set(base.relative(face.getOpposite()),Blocks.AIR.defaultBlockState());
            require(SceneBlockSupport.withScene(empty,()->level.getBlockState(base).triggerEvent(level,base,PistonBaseBlock.TRIGGER_CONTRACT,face.get3DDataValue())),"actual retraction accepted");finishMoving();
            require(!level.getBlockState(base).getValue(BlockStateProperties.EXTENDED),"actual base retracts");
            require(level.getBlockState(base.relative(face,sticky?1:2)).is(Blocks.STONE),"ordinary/sticky payload final position");
            rows.add(Map.of("case","actual_clear_cycle","direction",face.getName(),"sticky",sticky,"resolved",true));
        }
        piston(Direction.EAST,false,false);rememberMovement(Direction.EAST);
        set(base.east(),Blocks.STONE.defaultBlockState());set(base.east(2).below(),Blocks.DIRT.defaultBlockState());
        var flower=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:dandelion"));set(base.east(2),flower.defaultBlockState());
        set(base.west(),Blocks.REDSTONE_BLOCK.defaultBlockState());var wall=grid(server,full(base.east(2)));
        if(WorldBlocks.ENABLED){
            var area=new AABB(base.getX()-4,base.getY()-4,base.getZ()-4,base.getX()+5,base.getY()+5,base.getZ()+5);
            long drops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area).size();
            for(int i=0;i<100;i++){
                require(!SceneBlockSupport.withScene(wall,()->level.getBlockState(base).triggerEvent(level,base,PistonBaseBlock.TRIGGER_EXTEND,Direction.EAST.get3DDataValue())),"blocked actual event rejects before mutation");
                require(!level.getBlockState(base).getValue(BlockStateProperties.EXTENDED)&&level.getBlockState(base.east()).is(Blocks.STONE)&&level.getBlockState(base.east(2)).is(flower),"failed event keeps base/payload/destructible target");blockedAttempts++;
            }
            require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area).size()==drops,"blocked actions create no drops");
            rows.add(Map.of("case","actual_blocked_100","attempts",blockedAttempts,"resolved",false));
        }
        // A sticky piston still retracts if its payload cannot be pulled, exactly like an immovable MC obstacle.
        piston(Direction.EAST,true,true);rememberMovement(Direction.EAST);
        set(base.east(),Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.EAST));set(base.east(2),Blocks.STONE.defaultBlockState());
        var pullWall=grid(server,full(base.east()));
        require(SceneBlockSupport.withScene(pullWall,()->level.getBlockState(base).triggerEvent(level,base,PistonBaseBlock.TRIGGER_CONTRACT,Direction.EAST.get3DDataValue())),"blocked payload does not prevent base retraction");finishMoving();
        require(!level.getBlockState(base).getValue(BlockStateProperties.EXTENDED)&&level.getBlockState(base.east(WorldBlocks.ENABLED?2:1)).is(Blocks.STONE),"blocked sticky payload remains / vanilla baseline pulls");
        rows.add(Map.of("case","actual_blocked_pull","payload_at",WorldBlocks.ENABLED?2:1,"resolved",true));
    }
    void tick(MinecraftServer server){
        if(done||server.getPlayerList().getPlayers().isEmpty()||needNative&&!ready)return;
        done=true;level=server.overworld();
        try{
            var empty=grid(server);
            for(var face:Direction.values()){
                piston(face,false,false);set(base.relative(face),Blocks.STONE.defaultBlockState());
                query("empty",face,true,empty,true);
                query("head_wall",face,true,grid(server,full(base.relative(face))),!WorldBlocks.ENABLED);
                query("payload_wall",face,true,grid(server,full(base.relative(face,2))),!WorldBlocks.ENABLED);
                piston(face,false,false);query("empty_head_wall",face,true,grid(server,full(base.relative(face))),!WorldBlocks.ENABLED);
                for(int count:new int[]{12,13}){piston(face,false,false);for(int i=1;i<=count;i++)set(base.relative(face,i),Blocks.STONE.defaultBlockState());
                    query("push_limit_"+count,face,true,empty,count==12);}
                for(var obstacle:List.of(Blocks.OBSIDIAN,Blocks.BARREL)){
                    piston(face,false,false);set(base.relative(face),obstacle.defaultBlockState());query("vanilla_immovable_"+obstacle,face,true,empty,false);}
                piston(face,true,true);set(base.relative(face,2),Blocks.STONE.defaultBlockState());
                query("clear_pull",face,false,empty,true);
                query("blocked_pull",face,false,grid(server,full(base.relative(face))),!WorldBlocks.ENABLED);
                query("move_out_of_existing_overlap",face,false,grid(server,full(base.relative(face,2))),true);
            }
            piston(Direction.EAST,false,false);set(base.east(),Blocks.STONE_SLAB.defaultBlockState());
            query("bottom_slab_clears_upper_geometry",Direction.EAST,true,grid(server,new Cell(base.east(2),0,4,0,8,8,8)),true);
            query("bottom_slab_hits_thin_post",Direction.EAST,true,grid(server,new Cell(base.east(2),3,0,0,4,8,8)),!WorldBlocks.ENABLED);
            piston(Direction.UP,true,true);set(base.above(2),Blocks.STONE_SLAB.defaultBlockState());
            query("pull_slab_sweep_hits_between_endpoints",Direction.UP,false,grid(server,new Cell(base.above(),0,4,0,8,8,8)),!WorldBlocks.ENABLED);
            for(boolean honey:new boolean[]{false,true}){
                piston(Direction.EAST,false,false);set(base.east(),(honey?Blocks.HONEY_BLOCK:Blocks.SLIME_BLOCK).defaultBlockState());
                set(base.east().above(),Blocks.STONE.defaultBlockState());set(base.east().south(),(honey?Blocks.SLIME_BLOCK:Blocks.HONEY_BLOCK).defaultBlockState());
                var clear=query("sticky_branch",Direction.EAST,true,empty,true);
                require(clear.getToPush().contains(base.east().above())&&!clear.getToPush().contains(base.east().south()),"stone branch attaches but honey/slime remain separate");
                query("attached_branch_wall",Direction.EAST,true,grid(server,full(base.east(2).above())),!WorldBlocks.ENABLED);
                query("unattached_neighbor_wall",Direction.EAST,true,grid(server,full(base.east(2).south())),true);
            }
            var other=server.getLevel(net.minecraft.world.level.Level.NETHER);
            require(other!=null,"other dimension loaded");
            require(SceneBlockSupport.withScene(grid(server,full(base.east())),()->PistonSceneGuard.allows(other,base,Direction.EAST,true,Direction.EAST,List.of())),"other dimension ignores Native geometry");
            eventCases(server,empty);
            write(server,"p8-piston-scene.json",Map.of("result","PASS","native_guard",WorldBlocks.ENABLED,"cases",rows,"blocked_attempts",blockedAttempts,"actual_native_motion",false));
            restore(server);LoggerFactory.getLogger("sifucraft").info("[piston-scene-test] PASS {} cases, {} blocked events; actual vanilla resolver/events; guard={}",rows.size(),blockedAttempts,WorldBlocks.ENABLED);
            if(needNative){restoredAt=System.currentTimeMillis();finished=true;}else Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
        }catch(Exception|AssertionError e){write(server,"p8-piston-scene-failure.json",Map.of("error",e.toString(),"cases",rows));restore(server);
            LoggerFactory.getLogger("sifucraft").error("[piston-scene-test] FAIL",e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer server){if(level==null)return;for(var pos:originals.keySet())level.removeBlockEntity(pos);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}
}
