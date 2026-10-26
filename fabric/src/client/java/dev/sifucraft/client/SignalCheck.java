package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.RedstoneContacts;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Installed vanilla tick traces and scoped NPC signal contacts, in a disposable save only. */
final class SignalCheck {
    private record Contact(String name,BlockPos pos,Block block,String actor,int count,boolean outside) {}
    private record Body(Contact contact,Entity entity) {}
    private final BlockPos base=new BlockPos(16,201,16);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<String,BlockPos> watch=new LinkedHashMap<>(),buttons=new LinkedHashMap<>();
    private final Map<String,Integer> durations=new LinkedHashMap<>();
    private final List<Contact> contacts=new ArrayList<>();
    private final List<Body> bodies=new ArrayList<>();
    private final Set<ChunkPos> chunks=new HashSet<>(),forced=new HashSet<>();
    private final JsonArray logicTrace=new JsonArray(),referenceTrace=new JsonArray(),proxyTrace=new JsonArray();
    private final int[] repeaterRise={-1,-1,-1,-1},repeaterFall={-1,-1,-1,-1};
    private final List<Integer> observerWidths=new ArrayList<>();
    private int warmup,tick,observerWidth;
    private ServerLevel level;
    private ServerPlayer player;
    private boolean done,guiChecked;
    private long started;
    private final boolean needNative=Boolean.getBoolean("sifucraft.signalNativeTest");
    private volatile boolean nativeReady,finished;
    private volatile long restoredAt;
    private boolean clientDone;
    SignalCheck(){if(!DevWorld.LEVEL_ID.startsWith("sifucraft-signal-check-"))throw new IllegalStateException("signal check requires disposable save");}
    void clientTick(Minecraft mc,boolean ready){
        if(!needNative||clientDone)return;
        nativeReady=ready&&SifuCraftClient.blockCollisionCurrent();
        if(!finished)return;
        require(System.currentTimeMillis()-restoredAt<30000,"final Native revision ACK deadline");
        if(System.currentTimeMillis()-restoredAt<1000||!SifuCraftClient.blockCollisionCurrent())return;
        try{AtomicFiles.write(mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("p8-signals-native-ack.json"),out->
            new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","fixture_restored",true,"native_revision_applied",true),out));
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
        clientDone=true;LoggerFactory.getLogger("sifucraft").info("[signal-test] Native final applied revision ACK after fixture cleanup PASS");mc.stop();
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("signals: "+why);}
    private BlockPos at(int x,int z){return base.offset(x,0,z);}
    private void set(BlockPos pos,BlockState state){
        var chunk=new ChunkPos(pos.getX()>>4,pos.getZ()>>4);
        if(chunks.add(chunk)&&level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);
        originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));
        require(originals.get(pos).isAir()||!originals.get(pos).hasBlockEntity(),"original fixture has no block entity");
        level.setBlockAndUpdate(pos,state);
    }
    private void block(BlockPos pos,BlockState state){set(pos.below(),Blocks.STONE.defaultBlockState());set(pos,state);}
    private void observe(String id,BlockPos pos){watch.put(id,pos);}
    private void source(BlockPos pos,boolean on){set(pos,(on?Blocks.REDSTONE_BLOCK:Blocks.AIR).defaultBlockState());}
    private boolean powered(BlockPos pos){return level.getBlockState(pos).getValue(BlockStateProperties.POWERED);}
    private int wire(BlockPos pos){return level.getBlockState(pos).getValue(BlockStateProperties.POWER);}
    private int comparator(){return ((ComparatorBlockEntity)level.getBlockEntity(at(0,45))).getOutputSignal();}
    private void use(BlockPos pos){level.getBlockState(pos).useWithoutItem(level,player,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
    private void fillBarrel(int slots){
        var barrel=(BarrelBlockEntity)level.getBlockEntity(at(-1,45));barrel.clearContent();
        for(int i=0;i<slots;i++)barrel.setItem(i,new ItemStack(Items.STONE,64));barrel.setChanged();
    }
    private void prepare(MinecraftServer server){
        level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();started=System.currentTimeMillis();
        for(int i=0;i<16;i++){block(at(i,0),Blocks.REDSTONE_WIRE.defaultBlockState());observe("wire"+i,at(i,0));}
        for(int z=1;z<=3;z++){block(at(7,z),Blocks.REDSTONE_WIRE.defaultBlockState());observe("branch"+z,at(7,z));}
        block(at(0,7),Blocks.REDSTONE_TORCH.defaultBlockState());observe("torch",at(0,7));
        set(at(-1,7).below(),Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,AttachFace.WALL).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST));
        for(int delay=1;delay<=4;delay++){
            var pos=at(0,12+delay*3);block(pos,Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST).setValue(BlockStateProperties.DELAY,delay));observe("repeater"+delay,pos);
        }
        block(at(0,35),Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST).setValue(BlockStateProperties.DELAY,2));
        block(at(0,34),Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));observe("locked",at(0,35));
        block(at(-1,45),Blocks.BARREL.defaultBlockState());block(at(0,45),Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST));observe("comparator",at(0,45));
        block(at(0,55),Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.WEST));observe("observer",at(0,55));
        int z=62;
        for(var b:BuiltInRegistries.BLOCK)if(b instanceof ButtonBlock){
            var pos=at(0,z);z+=2;block(pos,b.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,AttachFace.FLOOR));
            String id=BuiltInRegistries.BLOCK.getKey(b).toString();buttons.put(id,pos);observe(id,pos);
        }
        block(at(0,96),Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,AttachFace.FLOOR));observe("lever",at(0,96));
        int lane=0;
        for(var b:new Block[]{Blocks.STONE_PRESSURE_PLATE,Blocks.OAK_PRESSURE_PLATE,Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,Blocks.TRIPWIRE})
            for(String actor:List.of("proxy","mob","item"))contact(b,actor,1,false,lane++);
        contact(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,"proxy",2,false,lane++);
        contact(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,"proxy",11,false,lane++);
        contact(Blocks.STONE_PRESSURE_PLATE,"proxy",1,true,lane++);
        contact(Blocks.TRIPWIRE,"proxy",1,true,lane);
        LoggerFactory.getLogger("sifucraft").info("[signal-test] prepared {} button types, {} contact lanes; index={}",buttons.size(),contacts.size(),WorldBlocks.ENABLED);
    }
    private void contact(Block block,String actor,int count,boolean outside,int lane){
        var pos=at(10,104+lane*3);
        var c=new Contact(BuiltInRegistries.BLOCK.getKey(block)+"/"+actor+"/"+count+"/"+outside,pos,block,actor,count,outside);contacts.add(c);
        if(block==Blocks.TRIPWIRE){
            set(pos.west(2),Blocks.STONE.defaultBlockState());set(pos.east(4),Blocks.STONE.defaultBlockState());
            set(pos.west(),Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
            set(pos.east(3),Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST));
            for(int i=0;i<3;i++)block(pos.east(i),Blocks.TRIPWIRE.defaultBlockState());
        }else block(pos,block.defaultBlockState());
        for(int i=0;i<count;i++){
            Entity entity;
            if(actor.equals("proxy")){
                var proxy=new EnemyProxy(EnemyEntities.ENEMY_PROXY,level);
                proxy.mirror(new EnemyFrame.Entry(7000+bodies.size(),1,SifuFrame.Vec3.ZERO,0,35,92,60,60,Protocol.ENEMY_ALIVE,1,SifuFrame.Vec3.ZERO),new MapPlacement("signals",0,0,0));entity=proxy;
            }else if(actor.equals("mob")){
                var type=BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse("minecraft:cow"));
                var mob=(Mob)type.create(level,EntitySpawnReason.COMMAND);require(mob!=null,"reference mob created");mob.setNoAi(true);entity=mob;
            }else entity=new ItemEntity(level,0,0,0,new ItemStack(Items.STONE,64));
            entity.setNoGravity(true);entity.setPermanentlyInvulnerable(true);entity.setSilent(true);entity.setPos(pos.getX()+3.5,pos.getY()+.02,pos.getZ()+.5);entity.setDeltaMovement(Vec3.ZERO);
            require(level.addFreshEntity(entity),"contact entity added");bodies.add(new Body(c,entity));
        }
    }
    private void actions(){
        if(tick==2){source(at(-1,0),true);for(int d=1;d<=4;d++)source(at(-1,12+d*3),true);buttons.values().forEach(this::use);use(at(0,96));fillBarrel(1);}
        if(tick==4||tick==12)set(at(-1,55),(tick==4?Blocks.STONE:Blocks.AIR).defaultBlockState());
        if(tick==4)source(at(0,33),true);
        if(tick==8){use(at(-1,7).below());source(at(-1,35),true);}
        if(tick==10)fillBarrel(27);
        if(tick==12){source(at(-1,0),false);use(at(0,96));}
        if(tick==16){use(at(-1,7).below());source(at(0,33),false);}
        if(tick==18)source(at(0,44),true);
        if(tick==20)for(int d=1;d<=4;d++)source(at(-1,12+d*3),false);
        if(tick==22)use(at(0,45));
        if(tick==26)fillBarrel(1);
        if(tick==28)source(at(-1,35),false);
        if(tick==30)use(at(0,45));
        if(tick==34)source(at(0,44),false);
        if(tick==38)fillBarrel(0);
        // Set every pose before triggering any weighted plate, matching production's two passes.
        for(var body:bodies){
            var c=body.contact;var e=body.entity;if(e.isRemoved())continue;
            if(tick==20&&c.actor.equals("proxy")&&c.block==Blocks.STONE_PRESSURE_PLATE&&!c.outside){e.discard();continue;}
            boolean inside=tick>=12&&tick<20&&!c.outside;
            e.setPos(c.pos.getX()+(c.block==Blocks.TRIPWIRE?1.5:.5)+(inside?0:3),c.pos.getY()+.02,c.pos.getZ()+.5);e.setDeltaMovement(Vec3.ZERO);
        }
        if(tick>=12&&tick<20)for(var body:bodies)require(!body.entity.isRemoved(),"contact reference stays alive: "+body.contact.name);
        for(var body:bodies)if(!body.entity.isRemoved()){
            if(body.entity instanceof EnemyProxy proxy){var position=proxy.position();var velocity=proxy.getDeltaMovement();float health=proxy.getHealth();
                RedstoneContacts.touch(proxy);require(proxy.position().equals(position)&&proxy.getDeltaMovement().equals(velocity)&&proxy.getHealth()==health&&proxy.noPhysics,"signal contact preserves mirrored pose/velocity/health");}
            else body.entity.applyEffectsFromBlocks(body.entity.position(),body.entity.position());
        }
        if(tick==48)checkMenu();
    }
    private void checkMenu(){
        var barrel=(BarrelBlockEntity)level.getBlockEntity(at(-1,45));barrel.setItem(0,new ItemStack(Items.DIAMOND,3));barrel.setChanged();use(at(-1,45));
        var menu=player.containerMenu;require(menu!=player.inventoryMenu&&menu.getType()==MenuType.GENERIC_9x3,"barrel opens original container menu");
        menu.clicked(0,0,ContainerInput.PICKUP,player);require(menu.getCarried().getCount()==3&&barrel.getItem(0).isEmpty(),"original menu pickup");
        menu.clicked(1,0,ContainerInput.PICKUP,player);require(menu.getCarried().isEmpty()&&barrel.getItem(1).getCount()==3,"original menu placement conserves three items");
        player.closeContainer();guiChecked=true;
        var pos=at(0,15);for(int i=0;i<4;i++)use(pos);require(level.getBlockState(pos).getValue(BlockStateProperties.DELAY)==1,"repeater right-click cycles four delays");
    }
    private void sample(){
        var row=new JsonObject();row.addProperty("tick",tick);
        for(var entry:watch.entrySet()){
            var state=level.getBlockState(entry.getValue());row.addProperty(entry.getKey(),BlockStateKey.encode(state));
            if(WorldBlocks.ENABLED){var s=WorldBlocks.index().snapshot(SectionPos.of(entry.getValue()).asLong());
                var indexed=s==null?null:s.states().get(SectionPos.sectionRelativePos(entry.getValue()));
                require(state.isAir()?indexed==null:indexed==state,"full state index matches vanilla: "+entry.getKey());}
        }
        row.addProperty("analog_output",comparator());logicTrace.add(row);
        for(int d=1;d<=4;d++){
            boolean on=powered(at(0,12+d*3));if(on&&repeaterRise[d-1]<0)repeaterRise[d-1]=tick;
            if(!on&&repeaterRise[d-1]>=0&&repeaterFall[d-1]<0)repeaterFall[d-1]=tick;
        }
        for(var e:buttons.entrySet())if(tick>2&&!powered(e.getValue())&&!durations.containsKey(e.getKey()))durations.put(e.getKey(),tick-2);
        boolean observing=powered(at(0,55));if(observing)observerWidth++;else if(observerWidth>0){observerWidths.add(observerWidth);observerWidth=0;}
        var ref=new JsonObject();ref.addProperty("tick",tick);var proxy=new JsonObject();proxy.addProperty("tick",tick);
        for(var c:contacts){
            var state=level.getBlockState(c.block==Blocks.TRIPWIRE?c.pos.east():c.pos);int power=state.hasProperty(BlockStateProperties.POWER)?state.getValue(BlockStateProperties.POWER):state.getValue(BlockStateProperties.POWERED)?15:0;
            (c.actor.equals("proxy")?proxy:ref).addProperty(c.name,power);
            boolean expected=!c.outside&&(!c.actor.equals("proxy")||WorldBlocks.ENABLED)&&!(c.block==Blocks.STONE_PRESSURE_PLATE&&c.actor.equals("item"));
            if(tick==18){require((power>0)==expected,"actual AABB contact/filter "+c.name+" output="+power);
                if(c.block==Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE&&expected)require(power==c.count,"light plate counts entities, not stack size");
                if(c.block==Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE&&expected)require(power==(int)Math.ceil(c.count/10.0),"heavy plate strength");
                if(c.block==Blocks.TRIPWIRE)require(powered(c.pos.west())==expected&&powered(c.pos.east(3))==expected,"both tripwire hooks signal");}
            if(tick==44)require(power==0,"leaving/discard releases contact via original scheduled tick: "+c.name);
        }
        referenceTrace.add(ref);proxyTrace.add(proxy);
        if(tick==6){for(int i=0;i<16;i++)require(wire(at(i,0))==Math.max(0,15-i),"15-step wire attenuation "+i);require(wire(at(7,3))==5,"wire branch attenuation");}
        if(tick==18)for(int i=0;i<16;i++)require(wire(at(i,0))==0,"wire source removal converges");
        if(tick==12)require(level.getBlockState(at(0,35)).getValue(BlockStateProperties.LOCKED)&&!powered(at(0,35)),"side repeater locks pending input");
        if(tick==26)require(!level.getBlockState(at(0,35)).getValue(BlockStateProperties.LOCKED)&&powered(at(0,35)),"unlock propagates held input");
        if(tick==40)require(!powered(at(0,35)),"unlocked repeater releases");
        if(tick==14)require(!level.getBlockState(at(0,7)).getValue(BlockStateProperties.LIT),"torch inversion");
        if(tick==24)require(level.getBlockState(at(0,7)).getValue(BlockStateProperties.LIT),"torch recovery");
        if(tick==8||tick==14||tick==24||tick==32||tick==37||tick==44){int expected=switch(tick){case 8,37->1;case 14->15;default->0;};require(comparator()==expected,"container comparator mode/side input at tick "+tick+" output="+comparator());}
    }
    void tick(MinecraftServer server){
        if(done||server.getPlayerList().getPlayers().isEmpty())return;
        if(needNative&&level==null&&!nativeReady)return;
        try{
            if(level==null){prepare(server);return;}
            require(System.currentTimeMillis()-started<90000,"fixture chunk/tick deadline");
            if(chunks.stream().anyMatch(c->!level.isPositionEntityTicking(new BlockPos(c.x()*16+8,base.getY(),c.z()*16+8))))return;
            if(warmup++<40)return;
            actions();sample();if(++tick<64)return;
            for(int d=1;d<=4;d++)require(repeaterRise[d-1]==2+2*d&&repeaterFall[d-1]==20+2*d,"installed repeater delay "+d+" rise="+repeaterRise[d-1]+" fall="+repeaterFall[d-1]);
            require(durations.size()==buttons.size()&&durations.values().stream().allMatch(t->t==20||t==30),"each button has one vanilla release duration");
            require(observerWidths.equals(List.of(2,2)),"observer has exactly two two-tick pulses: "+observerWidths);require(guiChecked,"container menu transaction");
            var report=new JsonObject();report.addProperty("result","PASS");report.addProperty("derived_index",WorldBlocks.ENABLED);report.addProperty("npc_contacts",WorldBlocks.ENABLED);
            report.addProperty("reference_mob","minecraft:cow");
            report.addProperty("native_observed",false);report.addProperty("button_types",buttons.size());report.addProperty("contact_lanes",contacts.size());report.addProperty("menu_transaction",guiChecked);
            report.addProperty("native_shape_ack_required",needNative);
            var gson=new GsonBuilder().setPrettyPrinting().create();report.add("button_ticks",gson.toJsonTree(durations));report.add("logic_trace",logicTrace);report.add("reference_contacts",referenceTrace);report.add("proxy_contacts",proxyTrace);
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-signals.json"),out->gson.toJson(report,out));
            done=true;restore(server);LoggerFactory.getLogger("sifucraft").info("[signal-test] PASS 64 vanilla ticks, {} button types/{} contact lanes, wire/torch/repeaters/comparator/observer/container; index={}",buttons.size(),contacts.size(),WorldBlocks.ENABLED);
            if(needNative){restoredAt=System.currentTimeMillis();finished=true;}
            else Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
        }catch(Exception|AssertionError e){
            try{var report=new JsonObject();report.addProperty("tick",tick);report.addProperty("error",e.toString());report.add("logic_trace",logicTrace);
                report.add("reference_contacts",referenceTrace);report.add("proxy_contacts",proxyTrace);report.addProperty("torch_support_signal",level.getSignal(at(0,7).below(),Direction.DOWN));
                var entities=new JsonArray();for(var body:bodies){var row=new JsonObject();row.addProperty("lane",body.contact.name);row.addProperty("removed",body.entity.isRemoved());row.addProperty("ignores_triggers",body.entity.isIgnoringBlockTriggers());row.addProperty("no_physics",body.entity.noPhysics);entities.add(row);}report.add("entities",entities);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-signals-failure.json"),out->new GsonBuilder().setPrettyPrinting().create().toJson(report,out));
            }catch(Exception evidence){e.addSuppressed(evidence);}
            done=true;restore(server);LoggerFactory.getLogger("sifucraft").error("[signal-test] FAIL tick="+tick,e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer server){
        if(level==null)return;for(var body:bodies)body.entity.discard();bodies.clear();if(player!=null)player.closeContainer();
        for(var pos:originals.keySet())level.removeBlockEntity(pos);
        for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),2);originals.clear();
        for(var chunk:forced)level.setChunkForced(chunk.x(),chunk.z(),false);forced.clear();chunks.clear();
    }
}
