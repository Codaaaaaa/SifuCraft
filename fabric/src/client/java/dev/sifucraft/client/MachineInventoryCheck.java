package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.blocks.BlockStateKey;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Two independently world-ticked machine lanes, with separate inventory conservation checks. */
final class MachineInventoryCheck {
    private final String phase=System.getProperty("sifucraft.machineInventoryPhase","full");
    private final BlockPos origin=new BlockPos(112,200,16);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final Set<ChunkPos> forced=new HashSet<>(),chunks=new HashSet<>();
    private final List<BlockPos> compared=new ArrayList<>();private final List<Map<String,Object>> reports=new ArrayList<>();
    private ServerLevel level;private ServerPlayer player;private volatile boolean ready,finished;private boolean configured,preserve,frozenByTest;private int ticks;private long deadline,firstSession,savedTime,pendingDue;
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("machine inventories: "+why);}
    MachineInventoryCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-machine-inventory-check-"),"disposable save required");require(List.of("full","prepare","verify").contains(phase),"explicit valid phase");}
    void serverStarted(MinecraftServer server){if(phase.equals("verify")){require(!server.tickRateManager().isFrozen(),"fresh server clock");server.tickRateManager().setFrozen(true);frozenByTest=true;}}
    void clientTick(Minecraft mc,boolean gui){if(finished){mc.stop();return;}var view=WorldMotionWorld.current();ready=gui&&view!=null&&view.placement().map().equals("MotionFixture")&&view.shapesCurrent()&&SceneFluidGeometry.available(mc.level);}
    private BlockPos p(int lane,int x,int y,int z){return origin.offset(lane*32+x,y,z);}
    private void put(BlockPos pos,BlockState state){
        var chunk=new ChunkPos(pos.getX()>>4,pos.getZ()>>4);require(chunks.contains(chunk),"fixture remains in prepared chunks");
        var old=level.getBlockState(pos);require(!old.hasBlockEntity()||originals.containsKey(pos),"no original BE replaced");originals.putIfAbsent(pos.immutable(),old);level.setBlockAndUpdate(pos,state);
    }
    private Container box(BlockPos pos){return (Container)level.getBlockEntity(pos);}
    private int count(BlockPos pos,Item item){int n=0;var c=box(pos);for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private void block(int lane,int x,int y,int z,BlockState state){put(p(lane,x,y,z),state);if(lane==0)compared.add(p(0,x,y,z));}
    private void setup(){for(int lane=0;lane<2;lane++){
        block(lane,0,0,0,Blocks.CHEST.defaultBlockState());
        block(lane,0,1,0,Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.DOWN));
        block(lane,0,2,0,Blocks.CHEST.defaultBlockState());box(p(lane,0,2,0)).setItem(0,new ItemStack(Items.DIAMOND,20));
        put(p(lane,1,1,0),Blocks.REDSTONE_BLOCK.defaultBlockState());
        block(lane,4,0,0,Blocks.CHEST.defaultBlockState());for(int slot=0;slot<box(p(lane,4,0,0)).getContainerSize();slot++)box(p(lane,4,0,0)).setItem(slot,new ItemStack(Items.STONE,64));
        block(lane,4,1,0,Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.DOWN));box(p(lane,4,1,0)).setItem(0,new ItemStack(Items.DIAMOND,7));
        block(lane,8,0,0,Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST));box(p(lane,8,0,0)).setItem(0,new ItemStack(Items.DIAMOND,9));block(lane,9,0,0,Blocks.CHEST.defaultBlockState());
        block(lane,12,0,0,Blocks.CRAFTER.defaultBlockState());block(lane,12,0,-1,Blocks.CHEST.defaultBlockState());
        var crafter=(CrafterBlockEntity)level.getBlockEntity(p(lane,12,0,0));for(int slot=1;slot<9;slot++)crafter.setSlotState(slot,true);crafter.setItem(0,new ItemStack(Items.OAK_LOG,3));
        block(lane,0,0,5,Blocks.FURNACE.defaultBlockState());box(p(lane,0,0,5)).setItem(0,new ItemStack(Items.COBBLESTONE,3));box(p(lane,0,0,5)).setItem(1,new ItemStack(Items.COAL));
        block(lane,4,0,5,Blocks.SMOKER.defaultBlockState());box(p(lane,4,0,5)).setItem(0,new ItemStack(Items.BEEF,3));box(p(lane,4,0,5)).setItem(1,new ItemStack(Items.COAL));
        block(lane,8,0,5,Blocks.BLAST_FURNACE.defaultBlockState());box(p(lane,8,0,5)).setItem(0,new ItemStack(Items.RAW_IRON,3));box(p(lane,8,0,5)).setItem(1,new ItemStack(Items.COAL));
        block(lane,12,0,5,Blocks.BREWING_STAND.defaultBlockState());var brew=box(p(lane,12,0,5));for(int slot=0;slot<3;slot++){var bottle=new ItemStack(Items.POTION);bottle.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.WATER));brew.setItem(slot,bottle);}brew.setItem(3,new ItemStack(Items.NETHER_WART));brew.setItem(4,new ItemStack(Items.BLAZE_POWDER));
    }}
    private void compare(){for(var a:compared){var b=a.offset(32,0,0);require(level.getBlockState(a)==level.getBlockState(b),"state trajectory "+BlockStateKey.encode(level.getBlockState(a))+" at "+a+" tick="+ticks);var left=box(a);var right=box(b);for(int slot=0;slot<left.getContainerSize();slot++)require(ItemStack.matches(left.getItem(slot),right.getItem(slot)),"inventory/component trajectory "+a+" slot="+slot+" tick="+ticks);}}
    private void power(int lane,boolean on){for(var pos:List.of(p(lane,8,0,1),p(lane,12,0,1)))put(pos,(on?Blocks.REDSTONE_BLOCK:Blocks.AIR).defaultBlockState());}
    private void menus(){for(var pos:compared){var state=level.getBlockState(pos);var before=new ArrayList<ItemStack>();for(int i=0;i<box(pos).getContainerSize();i++)before.add(box(pos).getItem(i).copy());
        state.useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));require(player.containerMenu!=player.inventoryMenu,"original configuration menu "+state.getBlock());player.closeContainer();for(int i=0;i<before.size();i++)require(ItemStack.matches(before.get(i),box(pos).getItem(i)),"opening menu preserves items");}}
    private void checkResults(){for(int lane=0;lane<2;lane++){
        require(count(p(lane,0,2,0),Items.DIAMOND)==0&&count(p(lane,0,1,0),Items.DIAMOND)==0&&count(p(lane,0,0,0),Items.DIAMOND)==20,"unlocked hopper transferred exactly twenty");
        require(count(p(lane,4,1,0),Items.DIAMOND)==0&&count(p(lane,4,0,0),Items.DIAMOND)==7&&count(p(lane,4,0,0),Items.STONE)==26*64,"full container released only owned slot");
        require(count(p(lane,8,0,0),Items.DIAMOND)==6&&count(p(lane,9,0,0),Items.DIAMOND)==3,"dropper exactly three edges, no held power repeats");
        require(count(p(lane,12,0,0),Items.OAK_LOG)==0&&count(p(lane,12,0,-1),Items.OAK_PLANKS)==12,"three original crafter recipes produce twelve planks");
        for(var spec:List.of(new Object[]{0,Items.COBBLESTONE,Items.STONE},new Object[]{4,Items.BEEF,Items.COOKED_BEEF},new Object[]{8,Items.RAW_IRON,Items.IRON_INGOT})){
            var at=p(lane,(int)spec[0],0,5);require(count(at,(Item)spec[1])==0&&count(at,(Item)spec[2])==3&&count(at,Items.COAL)==0,"three original cooking outputs and one fuel");
        }
        var brew=box(p(lane,12,0,5));require(count(p(lane,12,0,5),Items.NETHER_WART)==0&&count(p(lane,12,0,5),Items.BLAZE_POWDER)==0,"one ingredient and one blaze fuel consumed");for(int slot=0;slot<3;slot++)require(brew.getItem(slot).get(DataComponents.POTION_CONTENTS).is(Potions.AWKWARD),"three original awkward potion components");
    }menus();reports.add(Map.of("world_ticks",ticks,"paired_container_positions",compared.size(),"hopper_items",20,"blocked_items",7,"dropper_edges",3,"crafter_planks",12,"cooking_outputs",9,"awkward_potions",3));}
    private java.nio.file.Path marker(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("p8-machine-persistence.json");}
    private Map<String,String> states(){var result=new java.util.TreeMap<String,String>();for(var pos:originals.keySet())result.put(Long.toString(pos.asLong()),BlockStateKey.encode(level.getBlockState(pos)));return result;}
    private Map<String,String> blockEntities(){var result=new java.util.TreeMap<String,String>();for(var pos:originals.keySet()){var be=level.getBlockEntity(pos);if(be!=null)result.put(Long.toString(pos.asLong()),be.saveWithFullMetadata(level.registryAccess()).toString());}return result;}
    private void pending(boolean before){for(int lane=0;lane<2;lane++){
        require(count(p(lane,9,0,0),Items.DIAMOND)==(before?2:3),"third dropper edge fires only at original pending tick");
        require(count(p(lane,12,0,-1),Items.OAK_PLANKS)==(before?8:12),"third craft fires only at original pending tick");
        if(before){require(level.getBlockTicks().hasScheduledTick(p(lane,8,0,0),Blocks.DROPPER),"original pending dropper tick retained");require(level.getBlockTicks().hasScheduledTick(p(lane,12,0,0),Blocks.CRAFTER),"original pending crafter tick retained");}
    }}
    private void checkpoint(MinecraftServer server)throws Exception{
        pending(true);require(!server.tickRateManager().isFrozen(),"ordinary world clock before checkpoint");server.tickRateManager().setFrozen(true);frozenByTest=true;
        firstSession=WorldMotionWorld.current().scope().nativeSession();savedTime=level.getGameTime();pendingDue=savedTime+3;
        var originalKeys=new java.util.TreeMap<String,String>();for(var e:originals.entrySet())originalKeys.put(Long.toString(e.getKey().asLong()),BlockStateKey.encode(e.getValue()));
        var report=new LinkedHashMap<String,Object>();report.put("phase","prepared");report.put("native_session",Long.toUnsignedString(firstSession));report.put("world_time",savedTime);report.put("pending_due",pendingDue);report.put("ticks",ticks);report.put("originals",originalKeys);report.put("states",states());report.put("block_entities",blockEntities());report.put("compared",compared.stream().map(BlockPos::asLong).toList());report.put("forced",forced.stream().map(c->List.of(c.x(),c.z())).toList());report.put("chunks",chunks.stream().map(c->List.of(c.x(),c.z())).toList());
        AtomicFiles.write(marker(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));preserve=true;server.saveEverything(false,true,true);finished=true;
        LoggerFactory.getLogger("sifucraft").info("[machine-inventory-test] PREPARED {} full BlockEntity NBT records at world_time={} pending_due={}; frozen before normal save/exit",blockEntities().size(),savedTime,pendingDue);
    }
    private void loadCheckpoint(MinecraftServer server)throws Exception{
        var data=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(marker(server))).getAsJsonObject();require(data.get("phase").getAsString().equals("prepared"),"single prepared save");
        firstSession=Long.parseUnsignedLong(data.get("native_session").getAsString());require(firstSession!=WorldMotionWorld.current().scope().nativeSession(),"new Native endpoint process/session");savedTime=data.get("world_time").getAsLong();pendingDue=data.get("pending_due").getAsLong();ticks=data.get("ticks").getAsInt();require(level.getGameTime()==savedTime,"frozen startup preserves exact world time");
        for(var e:data.getAsJsonObject("originals").entrySet())originals.put(BlockPos.of(Long.parseLong(e.getKey())),BlockStateKey.decode(e.getValue().getAsString()));
        for(var e:data.getAsJsonArray("compared"))compared.add(BlockPos.of(e.getAsLong()));
        for(var e:data.getAsJsonArray("chunks")){var xy=e.getAsJsonArray();var c=new ChunkPos(xy.get(0).getAsInt(),xy.get(1).getAsInt());chunks.add(c);level.getChunk(c.x(),c.z());}
        for(var e:data.getAsJsonArray("forced")){var xy=e.getAsJsonArray();forced.add(new ChunkPos(xy.get(0).getAsInt(),xy.get(1).getAsInt()));}
        var expected=new java.util.TreeMap<String,String>();for(var e:data.getAsJsonObject("states").entrySet())expected.put(e.getKey(),e.getValue().getAsString());require(states().equals(expected),"all saved machine BlockStates restored exactly");
        var actual=blockEntities();require(actual.size()==data.getAsJsonObject("block_entities").size(),"same number of saved BlockEntities");for(var e:data.getAsJsonObject("block_entities").entrySet())require(net.minecraft.nbt.TagParser.parseCompoundFully(e.getValue().getAsString()).equals(net.minecraft.nbt.TagParser.parseCompoundFully(actual.get(e.getKey()))),"complete saved BE NBT including inventories/timers/disabled slots "+e.getKey());
        pending(true);configured=true;server.tickRateManager().setFrozen(false);frozenByTest=false;
        LoggerFactory.getLogger("sifucraft").info("[machine-inventory-test] RELOADED {} exact complete BE NBT, states and pending ticks at world_time={}; new endpoint session",actual.size(),level.getGameTime());
    }
    void tick(MinecraftServer server){if(finished)return;try{
        if(level==null){if(!ready||server.getPlayerList().getPlayers().isEmpty())return;level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();deadline=System.currentTimeMillis()+90000;
            if(phase.equals("verify")){loadCheckpoint(server);return;}
            for(int lane=0;lane<2;lane++)for(int x=0;x<=13;x++)for(int z=-1;z<=5;z++){var pos=p(lane,x,0,z);var chunk=new ChunkPos(pos.getX()>>4,pos.getZ()>>4);if(chunks.add(chunk)&&level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);}return;}
        require(System.currentTimeMillis()<deadline,"bounded actual machine ticks");
        if(!configured){if(chunks.stream().anyMatch(c->!level.isPositionEntityTicking(new BlockPos(c.x()*16+8,origin.getY(),c.z()*16+8))))return;setup();configured=true;return;}
        ticks++;compare();if(phase.equals("verify")&&level.getGameTime()<=pendingDue)pending(level.getGameTime()<pendingDue);
        for(int lane=0;lane<2;lane++){
            if(ticks==59)require(count(p(lane,0,2,0),Items.DIAMOND)==20&&count(p(lane,0,0,0),Items.DIAMOND)==0,"locked hopper remains stopped");
            if(ticks==60)put(p(lane,1,1,0),Blocks.AIR.defaultBlockState());
            if(ticks==229)require(count(p(lane,4,1,0),Items.DIAMOND)==7,"full output does not consume items");
            if(ticks==230)box(p(lane,4,0,0)).setItem(0,ItemStack.EMPTY);
            if(ticks==1||ticks==13||ticks==25)power(lane,true);if(ticks==6||ticks==18||ticks==110)power(lane,false);
        }
        if(phase.equals("prepare")&&ticks==26){checkpoint(server);return;}
        if(ticks<650)return;checkResults();restore(server);var result=Map.of("result","PASS","phase",phase,"backend","actual MC machines with synthetic Native endpoint","first_native_session",Long.toUnsignedString(firstSession),"next_native_session",Long.toUnsignedString(WorldMotionWorld.current().scope().nativeSession()),"reports",reports);AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-machine-inventory.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result,out));if(phase.equals("verify"))AtomicFiles.write(marker(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result,out));finished=true;LoggerFactory.getLogger("sifucraft").info("[machine-inventory-test] PASS paired world ticks, menu access and independent item conservation; phase={} {}",phase,reports);
    }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[machine-inventory-test] FAIL",e);preserve=false;restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}}
    void restore(MinecraftServer server){if(preserve)return;if(frozenByTest){server.tickRateManager().setFrozen(false);frozenByTest=false;}if(level==null)return;if(player!=null)player.closeContainer();for(var p:originals.keySet())level.removeBlockEntity(p);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();}
}
