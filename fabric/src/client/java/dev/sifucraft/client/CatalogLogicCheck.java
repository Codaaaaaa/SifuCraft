package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Material-complete power edges and independently specified analog readouts in an isolated world. */
final class CatalogLogicCheck {
    private record Fixture(Block block,BlockPos pos){}
    private final List<Block> materials=new ArrayList<>();private final List<Fixture> fixtures=new ArrayList<>();
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final Set<ChunkPos> chunks=new HashSet<>(),forced=new HashSet<>();
    private final List<Map<String,Object>> reports=new ArrayList<>();private final List<ItemStack> inventory=new ArrayList<>();
    private final BlockPos origin=new BlockPos(160,201,16),analog=origin.south(60);
    private volatile boolean ready,finished;private ServerLevel level;private ServerPlayer player;private int stage,tick,analogCount;private long deadline;
    CatalogLogicCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-catalog-logic-check-"),"disposable save required");for(var b:BuiltInRegistries.BLOCK)if(b instanceof DoorBlock||b instanceof TrapDoorBlock||b instanceof FenceGateBlock||b instanceof CopperBulbBlock)materials.add(b);materials.sort(java.util.Comparator.comparing(b->BuiltInRegistries.BLOCK.getKey(b).toString()));require(materials.size()==65,"installed 65 material variants");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("catalog logic: "+why);}
    void clientTick(Minecraft mc,boolean gui){if(finished){mc.stop();return;}var v=WorldMotionWorld.current();ready=gui&&v!=null&&v.placement().map().equals("MotionFixture")&&v.shapesCurrent();}
    private void put(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity()||originals.containsKey(pos),"no saved BE replaced");originals.putIfAbsent(pos.immutable(),old);if(old.getBlock()!=state.getBlock())level.removeBlockEntity(pos);level.setBlock(pos,state,Block.UPDATE_ALL);}
    private void setup(){int i=0;for(var b:materials){var pos=origin.offset((i%8)*4,0,(i/8)*5);i++;put(pos.below(),Blocks.STONE.defaultBlockState());
        // Both halves exist before power notifications; vanilla will then maintain the pair.
        originals.putIfAbsent(pos,level.getBlockState(pos));level.setBlock(pos,b.defaultBlockState(),2);
        if(b instanceof DoorBlock){originals.putIfAbsent(pos.above(),level.getBlockState(pos.above()));level.setBlock(pos.above(),b.defaultBlockState().setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),2);}fixtures.add(new Fixture(b,pos));
    }}
    private void power(boolean on){for(var f:fixtures)put(f.pos.west(),(on?Blocks.REDSTONE_BLOCK:Blocks.AIR).defaultBlockState());}
    private void check(boolean powered,boolean bulbLit){for(var f:fixtures){var state=level.getBlockState(f.pos);require(state.is(f.block),"material remains placed "+f.block);
        require(state.getValue(BlockStateProperties.POWERED)==powered,"original power state "+BlockStateKey.encode(state));
        if(f.block instanceof CopperBulbBlock){require(state.getValue(BlockStateProperties.LIT)==bulbLit,"copper bulb edge latch "+state);require(state.getAnalogOutputSignal(level,f.pos,Direction.NORTH)==(bulbLit?15:0),"bulb comparator reads latched light");}
        else{require(state.getValue(BlockStateProperties.OPEN)==powered,"door/gate/trapdoor follows actual power "+state);if(f.block instanceof DoorBlock){var upper=level.getBlockState(f.pos.above());require(upper.is(f.block)&&upper.getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER&&upper.getValue(BlockStateProperties.OPEN)==powered&&upper.getValue(BlockStateProperties.POWERED)==powered,"door halves coherent");}}
    }}
    private void manual(boolean open){for(var f:fixtures){if(f.block instanceof CopperBulbBlock)continue;var before=level.getBlockState(f.pos);var result=before.useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(f.pos),Direction.NORTH,f.pos,false));boolean hand=f.block!=Blocks.IRON_DOOR&&f.block!=Blocks.IRON_TRAPDOOR;
        require(result.consumesAction()==hand,"original manual eligibility "+f.block);require(level.getBlockState(f.pos).getValue(BlockStateProperties.OPEN)==(hand&&open),"manual toggles exactly once without power "+f.block);
    }}
    private void signal(BlockState state,int expected){put(analog,state);int actual=level.getBlockState(analog).getAnalogOutputSignal(level,analog,Direction.NORTH);require(actual==expected,"analog "+BlockStateKey.encode(state)+" expected="+expected+" actual="+actual);reports.add(Map.of("state",BlockStateKey.encode(state),"analog",actual));analogCount++;}
    @SuppressWarnings({"unchecked","rawtypes"})private static BlockState value(BlockState state,Property p,Comparable v){return state.setValue(p,v);}
    private void analog(){
        put(analog.below(),Blocks.STONE.defaultBlockState());
        for(var b:BuiltInRegistries.BLOCK){
            var state=b.defaultBlockState();
            if(b instanceof BeehiveBlock)for(int n=0;n<=5;n++)signal(state.setValue(BeehiveBlock.HONEY_LEVEL,n),n);
            if(b instanceof CakeBlock)for(int n=0;n<=6;n++)signal(state.setValue(CakeBlock.BITES,n),14-2*n);
            if(b instanceof CandleCakeBlock)signal(state,14);
            if(b instanceof CauldronBlock)signal(state,0);
            if(b instanceof LavaCauldronBlock)signal(state,3);
            if(b instanceof LayeredCauldronBlock)for(int n=1;n<=3;n++)signal(state.setValue(LayeredCauldronBlock.LEVEL,n),n);
            if(b instanceof ComposterBlock)for(int n=0;n<=8;n++)signal(state.setValue(ComposterBlock.LEVEL,n),n);
            if(b instanceof RespawnAnchorBlock)for(int n=0;n<=4;n++)signal(state.setValue(RespawnAnchorBlock.CHARGE,n),n*15/4);
            if(b instanceof EndPortalFrameBlock){signal(state.setValue(EndPortalFrameBlock.HAS_EYE,false),0);signal(state.setValue(EndPortalFrameBlock.HAS_EYE,true),15);}
            if(b instanceof CopperGolemStatueBlock)for(var property:state.getProperties())if(property.getPossibleValues().iterator().next() instanceof CopperGolemStatueBlock.Pose)for(var option:property.getPossibleValues())signal(value(state,property,option),((Enum<?>)option).ordinal()+1);
        }put(analog,Blocks.AIR.defaultBlockState());
    }
    void tick(MinecraftServer server){if(finished)return;try{
        if(stage==0){if(!ready||server.getPlayerList().getPlayers().isEmpty())return;level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();deadline=System.currentTimeMillis()+45000;
            for(int x=0;x<=32;x++)for(int z=0;z<=61;z++){var at=origin.offset(x,0,z);var c=new ChunkPos(at.getX()>>4,at.getZ()>>4);if(chunks.add(c)&&level.setChunkForced(c.x(),c.z(),true))forced.add(c);}for(int i=0;i<player.getInventory().getContainerSize();i++)inventory.add(player.getInventory().getItem(i).copy());stage=1;return;}
        require(System.currentTimeMillis()<deadline,"bounded world-tick matrix");
        if(stage==1){if(chunks.stream().anyMatch(c->!level.isPositionEntityTicking(new BlockPos(c.x()*16+8,origin.getY(),c.z()*16+8))))return;setup();analog();if(Boolean.getBoolean("sifucraft.catalogContainerSelfTest"))reports.addAll(CatalogContainerCases.run(level,player,analog));check(false,false);stage=2;return;}
        tick++;if(tick==1||tick==40)power(true);if(tick==21||tick==50)power(false);
        if(tick==5||tick==20)check(true,true);if(tick==25)check(false,true);if(tick==30)manual(true);if(tick==35)manual(false);if(tick==45)check(true,false);if(tick<55)return;check(false,false);
        for(int i=0;i<inventory.size();i++)require(ItemStack.matches(inventory.get(i),player.getInventory().getItem(i)),"all player inventory components conserved");
        for(var f:fixtures)reports.add(Map.of("block",BuiltInRegistries.BLOCK.getKey(f.block).toString(),"two_power_edges",true,"held_power_does_not_repeat",true,"manual_checked",!(f.block instanceof CopperBulbBlock)));
        int count=fixtures.size();restore(server);AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-catalog-logic.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","powered_materials",count,"analog_cases",analogCount,"world_ticks",tick,"cases",reports),out));finished=true;LoggerFactory.getLogger("sifucraft").info("[catalog-logic-test] PASS {} powered material variants / {} analog cases, two edges/held level/release/manual, original world updates; restored",count,analogCount);
    }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[catalog-logic-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}}
    void restore(MinecraftServer ignored){if(level==null)return;for(var p:originals.keySet())level.removeBlockEntity(p);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();}
}
