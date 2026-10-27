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
    private final BlockPos origin=new BlockPos(112,200,16);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final Set<ChunkPos> forced=new HashSet<>(),chunks=new HashSet<>();
    private final List<BlockPos> compared=new ArrayList<>();private final List<Map<String,Object>> reports=new ArrayList<>();
    private ServerLevel level;private ServerPlayer player;private volatile boolean ready,finished;private boolean configured;private int ticks;private long deadline;
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("machine inventories: "+why);}
    MachineInventoryCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-machine-inventory-check-"),"disposable save required");}
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
    void tick(MinecraftServer server){if(finished)return;try{
        if(level==null){if(!ready||server.getPlayerList().getPlayers().isEmpty())return;level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();deadline=System.currentTimeMillis()+90000;
            for(int lane=0;lane<2;lane++)for(int x=0;x<=13;x++)for(int z=-1;z<=5;z++){var pos=p(lane,x,0,z);var chunk=new ChunkPos(pos.getX()>>4,pos.getZ()>>4);if(chunks.add(chunk)&&level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);}return;}
        require(System.currentTimeMillis()<deadline,"bounded actual machine ticks");
        if(!configured){if(chunks.stream().anyMatch(c->!level.isPositionEntityTicking(new BlockPos(c.x()*16+8,origin.getY(),c.z()*16+8))))return;setup();configured=true;return;}
        ticks++;compare();
        for(int lane=0;lane<2;lane++){
            if(ticks==59)require(count(p(lane,0,2,0),Items.DIAMOND)==20&&count(p(lane,0,0,0),Items.DIAMOND)==0,"locked hopper remains stopped");
            if(ticks==60)put(p(lane,1,1,0),Blocks.AIR.defaultBlockState());
            if(ticks==229)require(count(p(lane,4,1,0),Items.DIAMOND)==7,"full output does not consume items");
            if(ticks==230)box(p(lane,4,0,0)).setItem(0,ItemStack.EMPTY);
            if(ticks==1||ticks==13||ticks==25)power(lane,true);if(ticks==6||ticks==18||ticks==110)power(lane,false);
        }
        if(ticks<650)return;checkResults();restore(server);AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-machine-inventory.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","actual MC machines with synthetic Native endpoint","reports",reports),out));finished=true;LoggerFactory.getLogger("sifucraft").info("[machine-inventory-test] PASS paired world ticks, menu access and independent item conservation; {}",reports);
    }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[machine-inventory-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}}
    void restore(MinecraftServer ignored){if(level==null)return;if(player!=null)player.closeContainer();for(var p:originals.keySet())level.removeBlockEntity(p);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();}
}
