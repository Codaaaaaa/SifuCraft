package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.combat.VanillaPlayerBody;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual client-owned player physics and registered item physics in an isolated IPC scene. */
final class PlayerItemBubbleCheck {
    private record Spec(int index,int height,boolean down,int x){
        BlockPos base(){return new BlockPos(x,67,12);}
        Vec3 start(){return new Vec3(x+.5,68+(down?Math.max(.1,height-2):.1),12.5);}
    }
    private record Sample(int index,Vec3 position,Vec3 velocity,boolean ground,int air,float yaw){}
    private final List<Spec> cases=new ArrayList<>();private final List<Map<String,Object>> reports=new ArrayList<>();
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean ready,finished,clientDone;private volatile Spec active;
    private volatile Sample first;private volatile int observedTicks;private int localCase=-1,lastClientEntityTick=-1;
    private volatile double actualMin,actualMax,actualMinV,actualMaxV;
    private ServerLevel level;private ServerPlayer player;private Vec3 originalPlayer;private float originalYaw,originalPitch,originalHealth;private MobEffectInstance originalFire;
    private VanillaPlayerBody reference;private int refTicks,index,stage,itemTicks;
    private double refMin,refMax,refMinV,refMaxV,maxItemPosition,maxItemVelocity;private long deadline;
    private ItemEntity item,itemReference;
    PlayerItemBubbleCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-player-item-bubbles-check-"),"disposable save required");int i=0;for(int h:new int[]{1,4,16})for(boolean down:new boolean[]{false,true})cases.add(new Spec(i,h,down,8+4*i++));}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("player/item bubbles: "+why);}
    void clientTick(Minecraft mc,boolean gui){
        if(finished){mc.stop();return;}
        var view=WorldMotionWorld.current();ready=gui&&view!=null&&view.placement().map().equals("MotionFixture")&&view.shapesCurrent()&&SceneFluidGeometry.available(mc.level);
        if(deadline!=0)require(System.currentTimeMillis()<deadline,"bounded complete fixture deadline");
        var spec=active;if(spec==null||mc.player==null)return;
        if(localCase!=spec.index){localCase=spec.index;lastClientEntityTick=-1;observedTicks=0;clientDone=false;first=null;actualMin=Double.POSITIVE_INFINITY;actualMax=Double.NEGATIVE_INFINITY;actualMinV=actualMaxV=0;}
        var p=mc.player.position();if(Math.abs(p.x-(spec.x+.5))>.15||Math.abs(p.z-12.5)>.15||p.y<67.8||p.y>68+spec.height+3)return;
        if(mc.player.tickCount==lastClientEntityTick)return;lastClientEntityTick=mc.player.tickCount;
        require(!mc.player.getAbilities().flying,"actual survival player gravity is active");
        var velocity=mc.player.getDeltaMovement();
        if(first==null)first=new Sample(spec.index,p,velocity,mc.player.onGround(),mc.player.getAirSupply(),mc.player.getYRot());
        actualMin=Math.min(actualMin,p.y);actualMax=Math.max(actualMax,p.y);actualMinV=Math.min(actualMinV,velocity.y);actualMaxV=Math.max(actualMaxV,velocity.y);
        if(++observedTicks>=160)clientDone=true;
    }
    private void put(BlockPos p,BlockState state){var old=level.getBlockState(p);require(!old.hasBlockEntity(),"no saved BE replaced");originals.putIfAbsent(p.immutable(),old);level.setBlockAndUpdate(p,state);}
    private void build(){for(var spec:cases){var base=spec.base();put(base,(spec.down?Blocks.MAGMA_BLOCK:Blocks.SOUL_SAND).defaultBlockState());
        for(int y=1;y<=spec.height+4;y++){
            var p=base.above(y);for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL)put(p.relative(d),Blocks.GLASS.defaultBlockState());
            put(p,y<=spec.height?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState());
        }
        put(base.above(spec.height+4),Blocks.GLASS.defaultBlockState());
    }}
    private void teleport(Vec3 p){player.teleportTo(level,p.x,p.y,p.z,java.util.Set.of(),originalYaw,originalPitch,true);}
    private void startPlayer(){reference=null;refTicks=0;first=null;clientDone=false;active=cases.get(index);teleport(active.start());stage=2;}
    private void referenceTick(){var spec=active;var initial=first;if(initial==null||initial.index!=spec.index)return;
        if(reference==null){
            reference=new VanillaPlayerBody(level,30,90,spec.index+301);reference.setPos(initial.position);reference.setYRot(initial.yaw);reference.setOnGround(initial.ground);reference.setAirSupply(initial.air);reference.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,20000));reference.setDeltaMovement(initial.velocity);
            refMin=refMax=initial.position.y;refMinV=refMaxV=0;
        }
        reference.beginPrediction();level.tickNonPassenger(reference);refTicks++;
        refMin=Math.min(refMin,reference.getY());refMax=Math.max(refMax,reference.getY());refMinV=Math.min(refMinV,reference.getDeltaMovement().y);refMaxV=Math.max(refMaxV,reference.getDeltaMovement().y);
    }
    private void playerResult(){var spec=active;
        require(refTicks>=140,"reference tick stream remained active: client="+observedTicks+" reference="+refTicks);
        require(Math.abs(actualMin-refMin)<.04&&Math.abs(actualMax-refMax)<.04,"actual client and vanilla player position extrema height="+spec.height+" down="+spec.down+" actual="+actualMin+".."+actualMax+" reference="+refMin+".."+refMax);
        require(Math.abs(actualMinV-refMinV)<.12&&Math.abs(actualMaxV-refMaxV)<.12,"actual client and vanilla player speed range height="+spec.height+" down="+spec.down+" actual="+actualMinV+".."+actualMaxV+" reference="+refMinV+".."+refMaxV);
        require(spec.down?actualMin<first.position.y-.05:actualMax>first.position.y+.1,"actual client moved in the original bubble direction");
        reports.add(Map.of("kind","actual_client_player","height",spec.height,"down",spec.down,"client_ticks",observedTicks,"reference_ticks",refTicks,"actual_y",List.of(actualMin,actualMax),"reference_y",List.of(refMin,refMax),"actual_velocity",List.of(actualMinV,actualMaxV),"reference_velocity",List.of(refMinV,refMaxV)));
        LoggerFactory.getLogger("sifucraft").info("[player-item-bubbles-test] player h={} down={} ticks={}/{} y={}..{} ref={}..{} velocity={}..{} ref={}..{}",spec.height,spec.down,observedTicks,refTicks,actualMin,actualMax,refMin,refMax,actualMinV,actualMaxV,refMinV,refMaxV);
        reference.discard();reference=null;active=null;
    }
    private ItemEntity makeItem(Vec3 p,int seed){var e=new ItemEntity(level,p.x,p.y,p.z,new ItemStack(Items.DIAMOND));e.setDeltaMovement(Vec3.ZERO);e.setNeverPickUp();e.getRandom().setSeed(seed);return e;}
    private void startItem(){var spec=cases.get(index);item=makeItem(spec.start(),spec.index+401);itemReference=makeItem(spec.start(),spec.index+401);
        // Installed ItemEntity staggers resting movement by (tickCount + id)%4.
        // The unregistered reference gets the same phase and a distinct ID.
        itemReference.setId(-1000000+Math.floorMod(item.getId(),4));
        require(level.addFreshEntity(item),"actual item registered for normal world ticking");itemTicks=0;maxItemPosition=maxItemVelocity=0;refMin=refMax=spec.start().y;stage=4;}
    void tick(MinecraftServer server){
        if(finished||stage==0&&!ready)return;
        try{
            if(stage==0){level=server.overworld();if(server.getPlayerList().getPlayers().isEmpty())return;player=server.getPlayerList().getPlayers().getFirst();originalPlayer=player.position();originalYaw=player.getYRot();originalPitch=player.getXRot();originalHealth=player.getHealth();var fire=player.getEffect(MobEffects.FIRE_RESISTANCE);originalFire=fire==null?null:new MobEffectInstance(fire);player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,20000));deadline=System.currentTimeMillis()+180000;build();stage=1;return;}
            require(System.currentTimeMillis()<deadline,"bounded complete fixture deadline");
            if(stage==1){if(!ready)return;for(var spec:cases)if(!level.getBlockState(spec.base().above(spec.height)).is(Blocks.BUBBLE_COLUMN))return;startPlayer();return;}
            if(stage==2){referenceTick();if(!clientDone||first==null||first.index!=active.index)return;playerResult();if(++index<cases.size()){startPlayer();return;}index=0;teleport(originalPlayer);startItem();return;}
            if(stage==4){
                require(!item.isRemoved()&&!itemReference.isRemoved(),"ordinary items survive the bubble case");level.tickNonPassenger(itemReference);itemTicks++;
                maxItemPosition=Math.max(maxItemPosition,item.position().distanceTo(itemReference.position()));maxItemVelocity=Math.max(maxItemVelocity,item.getDeltaMovement().distanceTo(itemReference.getDeltaMovement()));
                require(item.tickCount==itemReference.tickCount&&maxItemPosition<1e-6&&maxItemVelocity<1e-8,"registered/reference original item tick equivalence at "+itemTicks+" position="+maxItemPosition+" velocity="+maxItemVelocity);
                refMin=Math.min(refMin,item.getY());refMax=Math.max(refMax,item.getY());if(itemTicks<160)return;
                var spec=cases.get(index);require(spec.down?refMin<spec.start().y-.05:refMax>spec.start().y+.1,"actual original item moves in the bubble direction");
                reports.add(Map.of("kind","registered_item","height",spec.height,"down",spec.down,"ticks",itemTicks,"position_error",maxItemPosition,"velocity_error",maxItemVelocity,"y",List.of(refMin,refMax)));
                LoggerFactory.getLogger("sifucraft").info("[player-item-bubbles-test] item h={} down={} ticks={} position_error={} velocity_error={}",spec.height,spec.down,itemTicks,maxItemPosition,maxItemVelocity);
                item.discard();itemReference.discard();item=itemReference=null;if(++index<cases.size()){startItem();return;}restore(server);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-player-item-bubbles.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","actual MC client/server with synthetic Native endpoint, not UE","cases",reports),out));
                finished=true;LoggerFactory.getLogger("sifucraft").info("[player-item-bubbles-test] PASS six actual client player and six registered item cases, original 1/4/16 columns; restored");
            }
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[player-item-bubbles-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){
        active=null;if(reference!=null)reference.discard();reference=null;if(item!=null)item.discard();if(itemReference!=null)itemReference.discard();item=itemReference=null;
        if(player!=null&&originalPlayer!=null){teleport(originalPlayer);player.setHealth(originalHealth);player.removeEffect(MobEffects.FIRE_RESISTANCE);if(originalFire!=null)player.addEffect(originalFire);}
        if(level!=null)for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();
    }
}
