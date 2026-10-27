package dev.sifucraft.client;

import com.mojang.authlib.GameProfile;
import dev.sifucraft.combat.VanillaPlayerBody;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Installed Player tick/travel differential, including positive/negative pickup controls. No Native motion. */
final class PlayerPhysicsCheck {
    private static final List<String> CASES=List.of("air_impulse","water_idle","water_swim","bubble_up","bubble_down","lava");
    private static final class Reference extends Player {
        Reference(ServerLevel level,long seed){super(level,new GameProfile(new UUID(0x5245464552454e43L,seed),"PlayerReference"));refreshDimensions();getRandom().setSeed(seed);}
        @Override public GameType gameMode(){return GameType.SURVIVAL;}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.8f);}
        @Override public boolean hurtServer(ServerLevel level,net.minecraft.world.damagesource.DamageSource source,float amount){return false;}
        void input(float forward,boolean jump,boolean sprint){xxa=0;zza=forward;jumping=jump;setSprinting(sprint);setShiftKeyDown(false);}
    }
    private static final class Pickup extends ItemEntity {
        int touches;
        Pickup(ServerLevel level,Vec3 position){super(level,position.x,position.y,position.z,new ItemStack(Items.DIAMOND));setNoGravity(true);noPhysics=true;setPickUpDelay(0);}
        @Override public void playerTouch(Player player){touches++;super.playerTouch(player);}
        @Override public boolean fireImmune(){return true;}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private final java.util.Set<net.minecraft.world.level.ChunkPos> forced=new java.util.HashSet<>();
    private volatile boolean finished;private ServerLevel level;
    private VanillaPlayerBody body;private Reference reference;private Pickup predictedPickup,referencePickup;
    private int scenario,warmup,tick;private long began;
    private double positionError,velocityError;private int swims;
    private static final Vec3 OFFSET=new Vec3(20,0,0);
    PlayerPhysicsCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-player-physics-check-"),"disposable save required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("player physics: "+why);}
    void clientTick(Minecraft client){if(finished)client.stop();}
    private void set(BlockPos position,BlockState state){var previous=level.getBlockState(position);require(!previous.hasBlockEntity(),"does not replace saved BE");originals.putIfAbsent(position.immutable(),previous);level.setBlockAndUpdate(position,state);}
    private BlockPos center(){return new BlockPos(24+scenario*40,70,24);}
    private void prepare(){
        var center=center();boolean fluid=scenario!=0;
        for(int lane:new int[]{0,20}){var chunk=new net.minecraft.world.level.ChunkPos((center.getX()+lane)>>4,center.getZ()>>4);if(forced.add(chunk))level.setChunkForced(chunk.x(),chunk.z(),true);}
        for(int lane:new int[]{0,20})for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++){
            set(center.offset(x+lane,-1,z),Blocks.STONE.defaultBlockState());
            for(int y=0;y<=6;y++)set(center.offset(x+lane,y,z),fluid&&(Math.abs(x)==4||Math.abs(z)==4)?Blocks.GLASS.defaultBlockState():Blocks.AIR.defaultBlockState());
            if(fluid&&Math.abs(x)<4&&Math.abs(z)<4)for(int y=0;y<4;y++)set(center.offset(x+lane,y,z),(scenario==5?Blocks.LAVA:Blocks.WATER).defaultBlockState());
        }
        if(scenario==3||scenario==4)for(int lane:new int[]{0,20})set(center.offset(lane,-1,0),(scenario==3?Blocks.SOUL_SAND:Blocks.MAGMA_BLOCK).defaultBlockState());
        long seed=821+scenario;
        body=new VanillaPlayerBody(level,35,90,seed);reference=new Reference(level,seed);
        body.setYRot(0);reference.setYRot(0);body.setXRot(0);reference.setXRot(0);
        body.setYHeadRot(0);reference.setYHeadRot(0);body.yBodyRot=0;reference.yBodyRot=0;
        require(body.getBbWidth()==reference.getBbWidth()&&body.getBbHeight()==reference.getBbHeight()&&body.getEyeHeight()==reference.getEyeHeight(),"identical initial player capsule and eye height");
        var position=Vec3.atBottomCenterOf(center).add(0,fluid?1:0,0);body.setPos(position);reference.setPos(position.add(OFFSET));body.setOnGround(!fluid);reference.setOnGround(!fluid);
        var velocity=scenario==0?new Vec3(0,1,0):Vec3.ZERO;body.setDeltaMovement(velocity);reference.setDeltaMovement(velocity);
        predictedPickup=new Pickup(level,position);referencePickup=new Pickup(level,position.add(OFFSET));
        require(level.addFreshEntity(predictedPickup)&&level.addFreshEntity(referencePickup),"pickup controls registered");
        warmup=0;tick=0;positionError=velocityError=0;swims=0;
    }
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            if(level==null){if(server.getPlayerList().getPlayers().isEmpty())return;level=server.overworld();began=System.currentTimeMillis();prepare();return;}
            require(System.currentTimeMillis()-began<120000,"bounded differential deadline");
            if(++warmup<40)return;
            if(tick==0&&(scenario==3||scenario==4))require(level.getBlockState(center()).is(Blocks.BUBBLE_COLUMN)&&level.getBlockState(center().above(3)).is(Blocks.BUBBLE_COLUMN),"original base creates full bubble column");
            float forward=scenario==2&&tick<70?.7f:0;boolean jump=scenario==2&&tick>=25&&tick<45,sprint=scenario==2&&tick<70;
            body.movement(0,forward,jump,sprint,false);reference.input(forward,jump,sprint);body.beginPrediction();
            if(predictedPickup.isAlive()){predictedPickup.setPos(body.position());predictedPickup.setDeltaMovement(Vec3.ZERO);}
            if(referencePickup.isAlive()){referencePickup.setPos(reference.position());referencePickup.setDeltaMovement(Vec3.ZERO);}
            level.tickNonPassenger(body);level.tickNonPassenger(reference);
            double p=body.position().distanceTo(reference.position().subtract(OFFSET)),v=body.getDeltaMovement().distanceTo(reference.getDeltaMovement());
            positionError=Math.max(positionError,p);velocityError=Math.max(velocityError,v);
            require(p<1e-7&&v<1e-9,"original player differential case="+CASES.get(scenario)+" tick="+tick+" position="+p+" velocity="+v+
                " body="+body.position()+"/"+body.getDeltaMovement()+" reference="+reference.position()+"/"+reference.getDeltaMovement()+
                " speed="+body.getSpeed()+"/"+reference.getSpeed()+" swim="+body.isSwimming()+"/"+reference.isSwimming()+" water="+body.isInWater()+"/"+reference.isInWater());
            require(body.tickCount==reference.tickCount&&body.getAirSupply()==reference.getAirSupply()&&body.isSwimming()==reference.isSwimming(),"same complete tick, air and swimming state");
            require(predictedPickup.isAlive()&&predictedPickup.touches==0&&body.getInventory().countItem(Items.DIAMOND)==0,"prediction cannot pick up items: alive="+predictedPickup.isAlive()+" touches="+predictedPickup.touches+" inventory="+body.getInventory().countItem(Items.DIAMOND));
            if(body.isSwimming())swims++;
            if(++tick<160)return;
            require(referencePickup.touches>0,"unmodified Player retains original pickup path");
            if(scenario==2)require(swims>0,"sprint input enters actual Player swimming branch");
            rows.add(Map.of("case",CASES.get(scenario),"ticks",tick,"position_error_m",positionError,"velocity_error",velocityError,"swimming_ticks",swims,"pickup_prediction",predictedPickup.touches,"pickup_reference",referencePickup.touches));
            LoggerFactory.getLogger("sifucraft").info("[player-physics-test] case={} ticks={} position_error={} velocity_error={} swimming_ticks={}",CASES.get(scenario),tick,positionError,velocityError,swims);
            clearBodies();if(++scenario<CASES.size()){prepare();return;}
            restore(server);AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-player-physics.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","original Player simulation only; no Native motion","rows",rows),out));
            finished=true;LoggerFactory.getLogger("sifucraft").info("[player-physics-test] PASS {} cases, original Player tick/travel/air/swimming and pickup isolation",rows.size());
        }catch(Exception|AssertionError error){LoggerFactory.getLogger("sifucraft").error("[player-physics-test] FAIL",error);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    private void clearBodies(){if(body!=null)body.discard();if(reference!=null)reference.discard();if(predictedPickup!=null)predictedPickup.discard();if(referencePickup!=null)referencePickup.discard();body=null;reference=null;predictedPickup=null;referencePickup=null;}
    void restore(MinecraftServer ignored){clearBodies();if(level!=null){for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),2);for(var chunk:forced)level.setChunkForced(chunk.x(),chunk.z(),false);}originals.clear();forced.clear();}
}
