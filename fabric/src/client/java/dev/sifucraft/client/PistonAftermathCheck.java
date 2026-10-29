package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.PistonActions;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Actual honey carry followed by support removal, independent vanilla body and OS physical endpoint. */
final class PistonAftermathCheck {
    private static final int HEIGHT=Integer.getInteger("sifucraft.aftermathHeight",2);
    private static final String VARIANT=System.getProperty("sifucraft.aftermathVariant","eastHoney");
    private static final boolean UP=VARIANT.startsWith("up"),HONEY=VARIANT.endsWith("Honey"),SLIME=VARIANT.endsWith("Slime");
    private static final Direction DIRECTION=UP?Direction.UP:Direction.EAST;
    private static final double START_Y=64+HEIGHT-(HONEY?.0625:0);
    private static final BlockPos BASE=UP?new BlockPos(4,62+HEIGHT,0):new BlockPos(3,63+HEIGHT,0);
    private static final BlockState PAYLOAD=(HONEY?Blocks.HONEY_BLOCK:SLIME?Blocks.SLIME_BLOCK:Blocks.STONE).defaultBlockState();
    private static final class Reference extends Cow {
        double peakY;
        Reference(ServerLevel level){super(EntityTypes.COW,level);refreshDimensions();setSilent(true);setPos(24.5,START_Y,.5);}
        @Override protected void registerGoals(){}
        @Override public void tick(){super.tick();peakY=Math.max(peakY,getY());}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
        @Override public boolean hurtServer(ServerLevel level,net.minecraft.world.damagesource.DamageSource source,float amount){LoggerFactory.getLogger("sifucraft").info("[piston-aftermath-reference] hurt={} amount={} y={} fall={} tick={}",source,amount,getY(),fallDistance,level.getGameTime());return super.hurtServer(level,source,amount);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean finished;private ServerLevel level;private Reference reference;private float referenceHp;private int stage;private long began,placedTick,completedTick;private double carry;private double maxFloorError,nativePeak,referencePeak;private boolean savedMembership,referenceRemoved;
    PistonAftermathCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-piston-aftermath-check-"),"disposable save required");FluidMotion.observe(new FluidMotion.Observer(){
        public void prepared(FluidMotion.Sample value){completed(value);}public void simulated(FluidMotion.Sample ignored){}
        public void completed(FluidMotion.Sample value){if(value.key().id()==101)nativePeak=Math.max(nativePeak,value.position().y);}
    });}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("piston aftermath: "+why);}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private void set(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"does not replace saved BE");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void clearMachine(int offset){
        set(BASE.relative(DIRECTION,2).offset(offset,0,0),Blocks.AIR.defaultBlockState());
        if(UP){set(BASE.relative(DIRECTION).offset(offset,0,0),Blocks.AIR.defaultBlockState());set(BASE.offset(offset,0,0),Blocks.AIR.defaultBlockState());set(BASE.below().offset(offset,0,0),Blocks.AIR.defaultBlockState());}
        if(UP&&offset==20&&BASE.getY()-1==63)set(BASE.below().offset(offset,0,0),Blocks.STONE.defaultBlockState());
    }
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var v=WorldMotionWorld.current();if(v==null||v.nativeStatus()==null||!v.shapesCurrent()||!v.placement().map().equals("MotionFixture"))return;
            var proxy=EnemyMirrors.hitAttacker(101,1);if(proxy==null)return;
            if(stage==0){
                require(Math.abs(proxy.getY()-START_Y)<.001,"synthetic endpoint initialized at the actual payload surface, not moved by this fixture");level=server.overworld();began=System.currentTimeMillis();placedTick=level.getGameTime();
                for(int offset:new int[]{0,20}){
                    set(BASE.offset(offset,0,0),Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,DIRECTION));
                    set(BASE.relative(DIRECTION).offset(offset,0,0),PAYLOAD);
                    set(BASE.relative(DIRECTION,2).offset(offset,0,0),Blocks.AIR.defaultBlockState());
                }
                for(int x=22;x<=29;x++)for(int z=-1;z<=1;z++)set(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState());
                reference=new Reference(level);referenceHp=reference.getHealth();require(level.addFreshEntity(reference),"ordinary independent land mob on its own honey source");stage=1;return;
            }
            require(System.currentTimeMillis()-began<25000,"deadline stage="+stage+" piston="+PistonMachines.stats()+" body="+FluidMotion.stats());
            nativePeak=Math.max(nativePeak,proxy.getY());referencePeak=Math.max(referencePeak,reference.peakY);
            if(stage==1){
                if(level.getGameTime()-placedTick<6||!reference.onGround())return;
                for(int offset:new int[]{0,20})set(BASE.relative(DIRECTION.getOpposite()).offset(offset,0,0),Blocks.REDSTONE_BLOCK.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                // Each independent mechanism removes its own launch platform
                // when its own action finishes. Waiting for the IPC side would
                // let the faster reference fall onto/bounce off an old platform.
                if(SLIME&&!referenceRemoved&&level.getBlockState(BASE.relative(DIRECTION,2).offset(20,0,0)).is(Blocks.SLIME_BLOCK)&&!(level.getBlockEntity(BASE.relative(DIRECTION).offset(20,0,0)) instanceof PistonMovingBlockEntity)){
                    clearMachine(20);referenceRemoved=true;
                }
                if(!savedMembership&&level.getBlockEntity(BASE.relative(DIRECTION,2)) instanceof PistonMovingBlockEntity moving){
                    var member=PistonActions.member(moving);require(member!=null&&member.token().positions().size()==2,"whole action includes payload and head");
                    var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());PistonActions.save(moving,output);
                    var clone=new PistonMovingBlockEntity(moving.getBlockPos(),moving.getBlockState(),moving.getMovedState(),moving.getDirection(),moving.isExtending(),moving.isSourcePiston());
                    PistonActions.load(clone,net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),output.buildResult()));
                    require(member.equals(PistonActions.member(clone)),"NBT roundtrip preserves group UUID, creation order and source head");savedMembership=true;
                }
                if(PistonMachines.stats().pending()!=0||PistonMachines.stats().released()<(SLIME?1:2)||!SLIME&&FluidMotion.stats().active()!=0||level.getBlockEntity(BASE.relative(DIRECTION,2)) instanceof PistonMovingBlockEntity||level.getBlockEntity(BASE.relative(DIRECTION)) instanceof PistonMovingBlockEntity)return;
                if(!SLIME){if(completedTick==0){completedTick=level.getGameTime();return;}if(level.getGameTime()-completedTick<6)return;}
                require(savedMembership,"moving group was observed and persisted");
                carry=UP?proxy.getY()-START_Y:proxy.getX()-4.5;
                if(SLIME)require(carry>.5&&FluidMotion.stats().acquired()>0,"real slime velocity continues beyond the actual piston contact");
                else{
                    require(Math.abs(carry-1)<.011&&(UP?Math.abs(proxy.getX()-4.5)<.001:Math.abs(proxy.getY()-START_Y)<.001),"actual Native endpoint carried 1m: x="+proxy.getX()+" y="+proxy.getY()+" start="+START_Y+" stats="+PistonMachines.stats()+" body="+FluidMotion.stats());
                    require(Math.abs(reference.getX()-20-proxy.getX())<.001&&Math.abs(reference.getY()-proxy.getY())<.001,"independent original mechanism has the same final endpoint: native="+proxy.position()+" reference="+reference.position()+" body="+FluidMotion.stats());
                }
                clearMachine(0);if(!referenceRemoved)clearMachine(20);stage=3;return;
            }
            if(stage==3){
                if(FluidMotion.stats().acquired()<1||FluidMotion.stats().active()!=0)return;
                maxFloorError=Math.max(Math.abs(proxy.getY()-64),Math.abs(reference.getY()-64));require(maxFloorError<.001,"actual endpoint and ordinary vanilla body fell to their matching floor: native="+proxy.position()+" reference="+reference.position()+" body="+FluidMotion.stats());
                require(FluidMotion.stats().released()==FluidMotion.stats().acquired()&&FluidMotion.stats().released()>=1&&FluidMotion.stats().failed()==0,"causal support loss and any piston gap restored every acquired body's original ticks without resetting/retrying it");
                float loss=referenceHp-reference.getHealth();float hp=v.enemies().entries().stream().filter(e->e.id()==101).findFirst().orElseThrow().health();
                require(Math.abs(60-hp-loss*3)<1e-5,"actual Native landing damage equals independent ordinary vanilla Cow final HP delta: mc_loss="+loss+" native_hp="+hp+" peak="+nativePeak+" reference_peak="+referencePeak+" piston="+PistonMachines.stats()+" body="+FluidMotion.stats());
                if(SLIME)require(Math.abs(nativePeak-referencePeak)<.035,"actual trajectory apex matches independent vanilla body: native="+nativePeak+" reference="+referencePeak);
                var stats=FluidMotion.stats();var report=new LinkedHashMap<String,Object>();report.put("result","PASS");report.put("backend","synthetic OS AABB endpoint, not UE");report.put("variant",VARIANT);report.put("checkpoint_displacement_m",carry);report.put("native_peak_y",nativePeak);report.put("reference_peak_y",referencePeak);report.put("max_floor_error_m",maxFloorError);report.put("mc_final_fall_loss",loss);report.put("native_hp",hp);report.put("body_stats",stats);report.put("piston_stats",PistonMachines.stats());report.put("group_nbt",savedMembership);
                restore(server);stage=4;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-piston-aftermath.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                LoggerFactory.getLogger("sifucraft").info("[piston-aftermath-test] PASS {} endpoint/apex/final damage vs independent original body, Native_HP={}, restored; report={}",VARIANT,hp,report);return;
            }
            if(stage==4&&v.shapesCurrent())finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[piston-aftermath-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){FluidMotion.observe(null);if(reference!=null)reference.discard();if(level!=null)for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());originals.clear();}
}
