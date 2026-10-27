package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.SifuFrame;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Real NPC water preparation, original upward honey piston, root-sized vanilla reference and safe return. */
final class NativeLiftCheck {
    private static final Vec3 OFFSET=new Vec3(20,200,0);
    private static final class Reference extends Cow {
        private float width=.7f,height=1.8f;
        Reference(ServerLevel level,float width,float height){super(EntityTypes.COW,level);this.width=width;this.height=height;refreshDimensions();setSilent(true);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(width>0?width:.7f,height>0?height:1.8f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean ready,finished;private int stage;private ServerLevel level;
    private EnemyFrame.Entry target;private BlockPos water,base,referenceBase;private Reference reference;
    private FluidMotion.Sample sample;private Vec3 originalRoot,platformRoot,liftedRoot,landedRoot;
    private long lastNs,began,deadline,settled,phaseTick;private double walked;private float playerHp,referenceHp;
    NativeLiftCheck(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-lift-native-check-"),"disposable save required");
        FluidMotion.observe(new FluidMotion.Observer(){
            public void prepared(FluidMotion.Sample value){if(target!=null&&target.key().equals(value.key())){sample=value;if(originalRoot==null)originalRoot=value.position();}}
            public void simulated(FluidMotion.Sample ignored){}
            public void completed(FluidMotion.Sample value){if(target!=null&&target.key().equals(value.key()))sample=value;}
        });
    }
    private static void require(boolean value,String why){if(!value)throw new AssertionError("native lift: "+why);}
    void clientTick(Minecraft mc,SifuFrame frame){
        if(finished){mc.stop();return;}
        var v=WorldMotionWorld.current();ready=frame!=null&&v!=null&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&v.shapesCurrent()&&(v.nativeStatus().capabilities()&1)!=0&&v.placement().map().equals("Hideout_1_Main");
        long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;
        if(!ready||mc.player==null||!frame.minecraftOwnsInput())return;
        if(began==0){began=System.currentTimeMillis();playerHp=frame.health();}require(frame.health()==playerHp,"Native player HP unchanged");
        require(System.currentTimeMillis()-began<120000,"scene deadline stage="+stage);
        if(stage==0&&v.enemies().entries().isEmpty()&&walked<4){var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);}
    }
    private void set(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"no saved BE replaced at "+pos);originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void drain(){if(water!=null)for(var pos:BlockPos.betweenClosed(water.offset(-12,-1,-12),water.offset(12,14,12)))if(!level.getFluidState(pos).isEmpty())level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());}
    void tick(MinecraftServer server){
        if(finished||!ready)return;
        try{
            var v=WorldMotionWorld.current();if(v==null)return;level=server.overworld();
            if(stage==0){
                for(var e:v.enemies().entries())if(e.alive()&&e.health()==60&&e.radius()==35&&e.halfHeight()==92){
                    var proxy=EnemyMirrors.hitAttacker(e.id(),e.spawn());if(proxy==null)continue;
                    var trial=new BlockPos(proxy.blockPosition().getX(),(int)Math.ceil(proxy.getY()-1e-5),proxy.blockPosition().getZ());boolean clear=true;
                    for(var pos:BlockPos.betweenClosed(trial.offset(-1,0,-1),trial.offset(1,11,1)))if(!level.getBlockState(pos).isAir()||!SceneBlockSupport.nativeShape(level,pos).isEmpty()){clear=false;break;}
                    if(!clear)continue;target=e;water=trial;deadline=System.currentTimeMillis()+70000;
                    for(var pos:BlockPos.betweenClosed(water.offset(-1,0,-1),water.offset(1,11,1)))set(pos,Blocks.WATER.defaultBlockState());
                    LoggerFactory.getLogger("sifucraft").info("[native-lift-test] water preparation target={}/{} walked={}m origin={}",e.id(),e.spawn(),walked,water);stage=1;return;
                }return;
            }
            if(stage==8){if(v.shapesCurrent())finished=true;return;}
            require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" body="+FluidMotion.stats()+" piston="+PistonMachines.stats());
            var e=v.enemies().entries().stream().filter(x->x.key().equals(target.key())).findFirst().orElseThrow();
            require(FluidMotion.stats().failed()==0&&PistonMachines.stats().releaseFailed()==0,"all acquired controls restore normally: body="+FluidMotion.stats()+" piston="+PistonMachines.stats());
            if(stage==1){
                if(sample==null||sample.position().y-originalRoot.y<3.3)return;
                var floor=BlockPos.containing(sample.position().x,Math.floor(sample.position().y)-1,sample.position().z);base=floor.below();referenceBase=base.offset(20,200,0);
                drain();set(base,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.UP));set(floor,Blocks.HONEY_BLOCK.defaultBlockState());set(base.above(2),Blocks.AIR.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                if(FluidMotion.stats().active()!=0||FluidMotion.stats().released()!=1)return;
                platformRoot=sample.position();require(Math.abs(platformRoot.y-(base.getY()+1.9375))<.03,"water handback landed on actual honey surface");
                require(Math.abs(originalRoot.y-Math.round(originalRoot.y))<.03,"reference floor matches actual original floor");
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)set(new BlockPos(base.getX()+20+x,(int)Math.round(originalRoot.y)+199,base.getZ()+z),Blocks.STONE.defaultBlockState());
                set(referenceBase,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.UP));set(referenceBase.above(),Blocks.HONEY_BLOCK.defaultBlockState());set(referenceBase.above(2),Blocks.AIR.defaultBlockState());
                reference=new Reference(level,sample.width(),sample.height());reference.setPos(platformRoot.add(OFFSET));reference.setOnGround(true);referenceHp=reference.getHealth();require(level.addFreshEntity(reference),"independent root-sized original body registered");phaseTick=level.getGameTime();stage=3;return;
            }
            if(stage==3){if(level.getGameTime()-phaseTick<5)return;set(base.below(),Blocks.REDSTONE_BLOCK.defaultBlockState());set(referenceBase.below(),Blocks.REDSTONE_BLOCK.defaultBlockState());stage=4;return;}
            if(stage==4){
                if(PistonMachines.stats().pending()!=0||PistonMachines.stats().released()<2||FluidMotion.stats().active()!=0||level.getBlockEntity(base.above(2)) instanceof PistonMovingBlockEntity)return;
                if(settled==0){settled=System.currentTimeMillis();return;}if(System.currentTimeMillis()-settled<400)return;
                liftedRoot=sample.position();require(Math.abs(liftedRoot.y-platformRoot.y-1)<.03,"actual real root rose one meter: "+liftedRoot.subtract(platformRoot));
                require(liftedRoot.distanceTo(reference.position().subtract(OFFSET))<.035,"real vertical endpoint matches independent original root body");
                require(e.health()==target.health(),"no damage while carrying or settling");
                for(var machine:new BlockPos[]{base,referenceBase})for(int y=-1;y<=2;y++)set(machine.above(y),Blocks.AIR.defaultBlockState());stage=5;return;
            }
            if(stage==5){
                if(FluidMotion.stats().active()!=0||FluidMotion.stats().acquired()<3||!reference.onGround())return;
                landedRoot=sample.position();require(landedRoot.distanceTo(originalRoot)<.06,"actual original floor reached without hover or pullback: "+landedRoot.subtract(originalRoot));
                require(Math.abs(reference.getY()-200-landedRoot.y)<.04,"independent original body has same landing height");
                float loss=referenceHp-reference.getHealth();if(Math.abs(e.health()-(target.health()-loss*3))>=.01)return;
                require(FluidMotion.stats().released()==FluidMotion.stats().acquired(),"every actual lease restored its three original ticks");settled=System.currentTimeMillis();stage=6;return;
            }
            if(stage==6){
                if(System.currentTimeMillis()-settled<3000)return;
                double hitY=v.placement().mcY(e.location().z()-e.halfHeight());require(Math.abs(hitY-(originalRoot.y-.025))<.08,"real hit mirror stable after handback");
                var report=new LinkedHashMap<String,Object>();report.put("result","PASS");report.put("target",target.id());report.put("spawn",target.spawn());report.put("original_root",originalRoot);report.put("platform_root",platformRoot);report.put("lifted_root",liftedRoot);report.put("landed_root",landedRoot);report.put("native_hp",e.health());report.put("mc_fall_loss",referenceHp-reference.getHealth());report.put("fluid",FluidMotion.stats());report.put("piston",PistonMachines.stats());
                restore(server);stage=8;AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-native-lift.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                LoggerFactory.getLogger("sifucraft").info("[native-lift-test] PASS actual water preparation/upward honey/original reference/fall/HP/three ticks/3s: {}",report);
            }
        }catch(Exception|AssertionError ex){LoggerFactory.getLogger("sifucraft").error("[native-lift-test] FAIL",ex);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(ex);});}
    }
    void restore(MinecraftServer ignored){FluidMotion.observe(null);if(reference!=null)reference.discard();if(level!=null){drain();for(var pos:originals.keySet())level.removeBlockEntity(pos);for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());}originals.clear();}
}
