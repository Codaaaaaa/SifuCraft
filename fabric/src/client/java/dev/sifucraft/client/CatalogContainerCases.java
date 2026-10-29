package dev.sifucraft.client;

import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Server-side original menus and special container interactions; physical GUI input is separate. */
final class CatalogContainerCases {
    private final ServerLevel level;private final ServerPlayer player;private final BlockPos pos;
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final List<ItemStack> inventory=new ArrayList<>();private final List<Entity> spawned=new ArrayList<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();private final int selected;private final ItemStack offhand;
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("catalog containers: "+why);}
    private CatalogContainerCases(ServerLevel l,ServerPlayer p,BlockPos at){level=l;player=p;pos=at;selected=p.getInventory().getSelectedSlot();offhand=p.getOffhandItem().copy();for(int i=0;i<p.getInventory().getContainerSize();i++)inventory.add(p.getInventory().getItem(i).copy());}
    private void put(BlockPos p,BlockState s){var old=level.getBlockState(p);require(!old.hasBlockEntity()||originals.containsKey(p),"no original BE replaced");originals.putIfAbsent(p.immutable(),old);level.removeBlockEntity(p);level.setBlock(p,s,2);}
    private InteractionResult use(BlockPos p,double x,double y){var hit=new BlockHitResult(new Vec3(p.getX()+x,p.getY()+y,p.getZ()),Direction.NORTH,p,false);return player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);}
    private ItemStack marked(net.minecraft.world.item.Item item,int count,String name){var stack=new ItemStack(item,count);stack.set(DataComponents.CUSTOM_NAME,Component.literal("SifuCraft "+name));return stack;}
    private void resetInventory(){for(int i=0;i<inventory.size();i++)player.getInventory().setItem(i,inventory.get(i).copy());player.getInventory().setSelectedSlot(0);player.setItemInHand(InteractionHand.OFF_HAND,offhand.copy());}
    private Container container(){return (Container)level.getBlockEntity(pos);}
    private List<Entity> entities(){return level.getEntities((Entity)null,new AABB(pos).inflate(3),e->true);}
    private void menus(){int count=0;for(var block:BuiltInRegistries.BLOCK){if(!(block instanceof ShulkerBoxBlock||block instanceof CopperChestBlock))continue;resetInventory();player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);put(pos.above(),Blocks.AIR.defaultBlockState());put(pos,block.defaultBlockState());
        var c=container();var owned=marked(Items.DIAMOND,3,"menu");c.setItem(0,owned.copy());var before=entities();require(use(pos,.5,.5).consumesAction()&&player.containerMenu!=player.inventoryMenu,"original menu opens "+block);var menu=player.containerMenu;
        menu.clicked(0,0,ContainerInput.PICKUP,player);require(ItemStack.matches(menu.getCarried(),owned)&&c.getItem(0).isEmpty(),"pick up components/count exactly");menu.clicked(1,0,ContainerInput.PICKUP,player);require(menu.getCarried().isEmpty()&&ItemStack.matches(c.getItem(1),owned),"deposit components/count exactly");player.closeContainer();require(entities().equals(before),"menu transaction emits no copied items");
        rows.add(Map.of("block",BuiltInRegistries.BLOCK.getKey(block).toString(),"menu",BuiltInRegistries.MENU.getKey(menu.getType()).toString(),"items_conserved",3));count++;
    }require(count==25,"installed 17 shulker and 8 copper chests");}
    private int ownedCount(ShelfBlockEntity shelf,ItemStack prototype){int count=0;for(int i=0;i<player.getInventory().getContainerSize();i++)if(ItemStack.isSameItemSameComponents(player.getInventory().getItem(i),prototype))count+=player.getInventory().getItem(i).getCount();for(int i=0;i<shelf.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(shelf.getItem(i),prototype))count+=shelf.getItem(i).getCount();return count;}
    private void shelves(){int count=0;for(var block:BuiltInRegistries.BLOCK){if(!(block instanceof ShelfBlock))continue;put(pos.south(),Blocks.STONE.defaultBlockState());put(pos,block.defaultBlockState());var shelf=(ShelfBlockEntity)level.getBlockEntity(pos);var hitSlots=new HashSet<Integer>();
        for(double x:new double[]{1.0/6,.5,5.0/6}){
            resetInventory();var owned=marked(Items.DIAMOND,3,"shelf");player.getInventory().setItem(0,owned.copy());player.getInventory().setItem(8,ItemStack.EMPTY);require(use(pos,x,.5).consumesAction(),"unpowered shelf deposits at front hit");
            int occupied=-1;for(int i=0;i<3;i++)if(!shelf.getItem(i).isEmpty()){require(occupied==-1,"one clicked shelf slot");occupied=i;}require(occupied>=0&&hitSlots.add(occupied)&&ownedCount(shelf,owned)==3,"three distinct slot hits and conservation");
            player.getInventory().setSelectedSlot(8);require(use(pos,x,.5).consumesAction()&&shelf.isEmpty()&&ownedCount(shelf,owned)==3,"empty main hand retrieves clicked stack without duplication");
        }
        for(int mask=0;mask<8;mask++){for(int i=0;i<3;i++)shelf.setItem(i,(mask&(1<<i))==0?ItemStack.EMPTY:marked(Items.DIAMOND,1,"signal"+i));for(var face:Direction.values())require(level.getBlockState(pos).getAnalogOutputSignal(level,pos,face)==(face==Direction.SOUTH?mask:0),"shelf 3-bit occupancy output only toward back");}
        resetInventory();var hotbar=new ArrayList<ItemStack>();var storage=new ArrayList<ItemStack>();for(int i=0;i<3;i++){hotbar.add(marked(Items.IRON_INGOT,i+2,"hotbar"+i));storage.add(marked(Items.GOLD_INGOT,i+5,"storage"+i));player.getInventory().setItem(6+i,hotbar.get(i).copy());shelf.setItem(i,storage.get(i).copy());}player.getInventory().setItem(0,ItemStack.EMPTY);
        level.setBlock(pos,level.getBlockState(pos).setValue(BlockStateProperties.POWERED,true),2);require(use(pos,.5,.5).consumesAction(),"powered shelf hotbar swap");for(int i=0;i<3;i++)require(ItemStack.matches(player.getInventory().getItem(6+i),storage.get(i))&&ItemStack.matches(shelf.getItem(i),hotbar.get(i)),"single powered shelf swaps rightmost hotbar slots");
        require(use(pos,.5,.5).consumesAction(),"powered shelf second swap");for(int i=0;i<3;i++)require(ItemStack.matches(player.getInventory().getItem(6+i),hotbar.get(i))&&ItemStack.matches(shelf.getItem(i),storage.get(i)),"second swap restores full components/counts");
        rows.add(Map.of("block",BuiltInRegistries.BLOCK.getKey(block).toString(),"front_slots",3,"analog_masks",8,"hotbar_roundtrip",true));count++;
    }require(count==13,"all installed shelf materials");}
    private void bookshelf(){put(pos,Blocks.CHISELED_BOOKSHELF.defaultBlockState());var shelf=(ChiseledBookShelfBlockEntity)level.getBlockEntity(pos);var slots=new HashSet<Integer>();
        for(double y:new double[]{.25,.75})for(double x:new double[]{1.0/6,.5,5.0/6}){resetInventory();player.getInventory().setItem(0,new ItemStack(Items.BOOK));player.getInventory().setItem(8,ItemStack.EMPTY);require(use(pos,x,y).consumesAction(),"original bookshelf insertion");int slot=shelf.getLastInteractedSlot();require(slots.add(slot)&&shelf.getItem(slot).is(Items.BOOK)&&level.getBlockState(pos).getAnalogOutputSignal(level,pos,Direction.NORTH)==slot+1,"six unique bookshelf slots and comparator last interaction");player.getInventory().setSelectedSlot(8);require(use(pos,x,y).consumesAction()&&shelf.isEmpty(),"original bookshelf removal");int books=0;for(int i=0;i<player.getInventory().getContainerSize();i++)if(player.getInventory().getItem(i).is(Items.BOOK))books+=player.getInventory().getItem(i).getCount();require(books==1,"book insert/remove conservation");}
        require(slots.size()==6,"all six bookshelf slots");rows.add(Map.of("block","minecraft:chiseled_bookshelf","slots",6,"last_interacted_comparator",true));
    }
    private void jukebox(){resetInventory();var disc=marked(Items.MUSIC_DISC_13,1,"record");player.setItemInHand(InteractionHand.MAIN_HAND,disc.copy());put(pos,Blocks.JUKEBOX.defaultBlockState());require(use(pos,.5,.5).consumesAction(),"original record insertion");var box=(JukeboxBlockEntity)level.getBlockEntity(pos);require(ItemStack.matches(box.getTheItem(),disc)&&player.getMainHandItem().isEmpty()&&box.getSongPlayer().isPlaying()&&box.getComparatorOutput()==1,"one record plays with original song/comparator");var before=entities();require(use(pos,.5,.5).consumesAction(),"original record eject");var added=entities().stream().filter(e->!before.contains(e)).toList();spawned.addAll(added);require(box.getTheItem().isEmpty()&&!box.getSongPlayer().isPlaying()&&box.getComparatorOutput()==0&&added.size()==1&&added.getFirst() instanceof ItemEntity&&ItemStack.matches(((ItemEntity)added.getFirst()).getItem(),disc),"single UUID record ejected and playback stopped");for(var e:added)e.discard();rows.add(Map.of("block","minecraft:jukebox","record_insert_eject",true,"original_song_started_stopped",true));}
    static List<Map<String,Object>> run(ServerLevel level,ServerPlayer player,BlockPos pos){
        require(Boolean.getBoolean("sifucraft.catalogContainerSelfTest")&&DevWorld.LEVEL_ID.startsWith("sifucraft-catalog-logic-check-"),"explicit disposable container fixture");require(level.isPositionEntityTicking(pos),"entity-ticking source for output UUID validation");var check=new CatalogContainerCases(level,player,pos);
        try{check.put(pos.below(),Blocks.STONE.defaultBlockState());check.menus();check.shelves();check.bookshelf();check.jukebox();AtomicFiles.write(level.getServer().getWorldPath(LevelResource.ROOT).resolve("p8-catalog-containers.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","rows",check.rows),out));LoggerFactory.getLogger("sifucraft").info("[catalog-container-test] PASS 25 container menus, 13 shelf materials/slots/bitmask/hotbar, six bookshelf slots, original jukebox record lifecycle");return List.copyOf(check.rows);}
        catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}finally{player.closeContainer();for(var e:check.spawned)if(!e.isRemoved())e.discard();for(var p:check.originals.keySet())level.removeBlockEntity(p);for(var e:check.originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);for(int i=0;i<check.inventory.size();i++)player.getInventory().setItem(i,check.inventory.get(i).copy());player.getInventory().setSelectedSlot(check.selected);player.setItemInHand(InteractionHand.OFF_HAND,check.offhand.copy());}
    }
}
