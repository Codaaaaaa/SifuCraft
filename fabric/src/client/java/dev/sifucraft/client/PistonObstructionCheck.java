package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.*;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import org.slf4j.LoggerFactory;

/** Production BE transactions against a known MC wall or an unpublished OS physical ceiling. */
final class PistonObstructionCheck {
    private static final class Reference extends Cow {
        private float width=.7f,height=1.84f;
        Reference(ServerLevel level,float width,float height){super(EntityTypes.COW,level);this.width=width;this.height=height;refreshDimensions();setNoAi(true);setNoGravity(true);setSilent(true);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(width,height);}
    }
    private final boolean ceiling=System.getProperty("sifucraft.pistonObstructionCase","mc-wall").equals("native-ceiling");
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private ServerLevel level;private EnemyFrame.Entry target;private Vec3 start,referenceStart;
    private BlockPos base,wall,referenceOffset=new BlockPos(20,0,0);private Direction direction;private Reference reference;
    private volatile boolean ready,finished;private int stage;private long began,blockedAt;private long lastNs;private double walked;
    private WorldMotionProtocol.Reply binding,obstruction;private boolean referencePowered;
    PistonObstructionCheck(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-piston-obstruction-check-"),"disposable save required");
        PistonMachines.observePrepared((key,receipt)->{
            if(target==null||!key.equals(target.key())||binding!=null)return;binding=receipt;
            var view=WorldMotionWorld.current();var offset=receipt.rootOffset();
            referenceStart=new Vec3(view.placement().mcX(receipt.position().x()+offset.x()),
                view.placement().mcY(receipt.position().z()+offset.z()-receipt.rootHalfHeight()),view.placement().mcZ(receipt.position().y()+offset.y()));
            referenceStart=referenceStart.add(referenceOffset.getX(),referenceOffset.getY(),referenceOffset.getZ());
            reference=new Reference(level,receipt.rootRadius()/50,receipt.rootHalfHeight()/50);reference.setPos(referenceStart);reference.setOnGround(true);
            require(level.addFreshEntity(reference),"independent original root-sized body registered");
        });
        PistonMachines.observeMotion((key,receipt)->{
            if(target==null||!key.equals(target.key())||(receipt.status()!=4&&receipt.status()!=5))return;
            var proxy=EnemyMirrors.hitAttacker(key.id(),key.spawn());var view=WorldMotionWorld.current();
            var actual=new Vec3(view.placement().mcX(receipt.position().x()),view.placement().mcY(receipt.position().z()-target.halfHeight()),view.placement().mcZ(receipt.position().y()));
            require(proxy!=null&&proxy.position().distanceTo(actual)<1e-5,"partial endpoint mirrored before cancelling the uncommitted BE");
            if(obstruction==null){obstruction=receipt;report.put("partial_mirrored_before_cancel",true);}
        });
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston obstruction: "+why);}
    void clientTick(Minecraft mc,boolean gui){
        if(finished){mc.stop();return;}
        var view=WorldMotionWorld.current();ready=gui&&view!=null&&view.nativeStatus()!=null&&view.nativeStatus().flags()==0&&view.shapesCurrent();
        long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;
        if(stage==0&&ready&&mc.player!=null&&!view.placement().map().equals("MotionFixture")&&view.enemies().entries().isEmpty()&&walked<4){
            var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);
        }
    }
    private void set(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"replaces no saved block entity");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void machine(BlockPos origin){
        set(origin,Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,direction));
        set(origin.relative(direction),Blocks.STONE.defaultBlockState());
        set(origin.relative(direction,2),Blocks.AIR.defaultBlockState());
    }
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var view=WorldMotionWorld.current();if(view==null||view.enemies()==null)return;
            if(stage==0){
                if(!ready)return;level=server.overworld();
                for(var entry:view.enemies().entries()){
                    if(!entry.alive()||entry.health()!=60||ceiling&&entry.id()!=101)continue;
                    var proxy=EnemyMirrors.hitAttacker(entry.id(),entry.spawn());if(proxy==null)continue;
                    if(ceiling){require(view.placement().map().equals("MotionFixture"),"unpublished ceiling uses only the explicit OS simulator");base=new BlockPos(4,64,0);direction=Direction.UP;}
                    else{
                        var foot=BlockPos.containing(proxy.getX(),Math.ceil(proxy.getY()-1e-5),proxy.getZ());
                        for(var face:List.of(Direction.EAST,Direction.WEST,Direction.SOUTH,Direction.NORTH)){
                            var trial=foot.relative(face,-2);boolean clear=!proxy.getBoundingBox().intersects(new AABB(trial.relative(face)));
                            for(var pos:List.of(trial,trial.relative(face),trial.relative(face,2),trial.relative(face,-1),foot.relative(face),foot.relative(face).above()))
                                if(!level.getBlockState(pos).isAir()||SceneBlockSupport.intersects(level,net.minecraft.world.phys.shapes.Shapes.create(new AABB(pos))))clear=false;
                            if(clear){base=trial;direction=face;wall=foot.relative(face);break;}
                        }
                        if(base==null)continue;
                    }
                    target=entry;start=proxy.position();break;
                }
                if(target==null)return;
                if(!view.placement().map().equals("MotionFixture")){
                    referenceOffset=null;var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());
                    for(int distance:new int[]{4,-4,6,-6,8,-8})for(var shift:List.of(new BlockPos(distance,0,0),new BlockPos(0,0,distance))){
                        var area=proxy.getBoundingBox().move(shift.getX(),0,shift.getZ()).expandTowards(Vec3.atLowerCornerOf(direction.getUnitVec3i()));
                        area=new AABB(area.minX,area.minY+.1,area.minZ,area.maxX,area.maxY,area.maxZ);
                        boolean clear=SceneFluidGeometry.knownVolume(level,area);
                        for(var shape:level.getBlockCollisions(proxy,area))if(!shape.isEmpty())clear=false;
                        for(var cell:List.of(base,base.relative(direction),base.relative(direction,2),base.relative(direction.getOpposite())))if(!level.getBlockState(cell.offset(shift)).isAir()||!SceneBlockSupport.nativeShape(level,cell.offset(shift)).isEmpty())clear=false;
                        if(clear){referenceOffset=shift;break;}
                    }
                    require(referenceOffset!=null,"independent reference mechanism fits the actual Native scene");
                }
                began=System.currentTimeMillis();
                for(var offset:List.of(BlockPos.ZERO,referenceOffset)){
                    for(int x=-2;x<=4;x++)for(int z=-2;z<=2;z++)set(base.offset(offset).offset(x,ceiling?1:-1,z),Blocks.GLASS.defaultBlockState());
                    machine(base.offset(offset));
                    if(wall!=null){set(wall.offset(offset),Blocks.STONE.defaultBlockState());set(wall.above().offset(offset),Blocks.STONE.defaultBlockState());}
                }
                report.put("backend",view.placement().map().equals("MotionFixture")?"separate OS AABB endpoint, not UE":"actual Native root capsule");
                report.put("case",ceiling?"unpublished-native-ceiling":"known-mc-wall");report.put("target",target.key());stage=1;return;
            }
            require(System.currentTimeMillis()-began<25000,"deadline stage="+stage+" machines="+PistonMachines.stats());
            var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());require(proxy!=null,"same actual activation remains published");
            require(Math.abs(proxy.getHealth()-target.health()/3)<.001,"no invented crushing damage before a solid commit");
            if(stage==1){if(!ready)return;set(base.relative(direction.getOpposite()),Blocks.REDSTONE_BLOCK.defaultBlockState());stage=2;return;}
            if(stage==2){
                if(binding==null||PistonMachines.stats().cancelRestored()==0||PistonMachines.stats().pending()!=0||ceiling&&obstruction==null)return;
                var payload=level.getBlockEntity(base.relative(direction,2));
                require(payload instanceof PistonMovingBlockEntity&&((PistonMovingBlockEntity)payload).getProgress(1)==0,"obstruction never commits the payload half-step");
                require(PistonMachines.stats().committed()==0,"no new solid is published through an unmoved or partially moved body");
                if(ceiling){
                    double dy=proxy.getY()-start.y;require(dy>.01&&dy<.51,"actual unpublished obstruction produced a partial displacement");
                    require(PistonMachines.stats().refundedCm()==0,"partial/unknown swept requests never refund the action budget");
                    report.put("actual_partial_m",dy);report.put("partial_receipt",obstruction);
                }else require(proxy.position().distanceTo(start)<.001&&PistonMachines.stats().applied()==0,"known clipping is rejected before any physical STEP");
                if(!referencePowered){set(base.relative(direction.getOpposite()).offset(referenceOffset),Blocks.REDSTONE_BLOCK.defaultBlockState());referencePowered=true;blockedAt=System.currentTimeMillis();return;}
                if(System.currentTimeMillis()-blockedAt<700)return;
                require(level.getBlockState(base.relative(direction,2).offset(referenceOffset)).is(Blocks.STONE),"independent original piston completed against its own obstacle");
                report.put("reference_delta",reference.position().subtract(referenceStart).toString());report.put("machines",PistonMachines.stats());
                if(!ceiling){
                    require(reference.position().distanceTo(referenceStart)>.001,"original reference demonstrates the wall-clipped movement which the bridge conservatively jams");
                    set(wall,Blocks.AIR.defaultBlockState());set(wall.above(),Blocks.AIR.defaultBlockState());stage=3;return;
                }
                restore(server);stage=4;return;
            }
            if(stage==3){
                if(!ready||PistonMachines.stats().pending()!=0||!level.getBlockState(base.relative(direction,2)).is(Blocks.STONE))return;
                require(proxy.position().distanceTo(start)>.5&&PistonMachines.stats().released()>0&&PistonMachines.stats().releaseFailed()==0,"removing the preflight obstruction permits a complete actual stroke and handback");
                report.put("resumed_delta",proxy.position().subtract(start).toString());report.put("machines_after_resume",PistonMachines.stats());restore(server);stage=4;return;
            }
            if(stage==4){
                if(!ready||PistonMachines.stats().pending()!=0||view.requests().pending()!=0)return;
                require(PistonMachines.stats().cancelRestoreFailed()==0,"all acquired cancelled transactions prove original tick restoration");
                report.put("passed",true);report.put("final_machines",PistonMachines.stats());
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-piston-obstruction.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                LoggerFactory.getLogger("sifucraft").info("[piston-obstruction-test] PASS {}",report);finished=true;
            }
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[piston-obstruction-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){
        PistonMachines.observePrepared(null);PistonMachines.observeMotion(null);if(reference!=null)reference.discard();
        if(level!=null){for(var pos:originals.keySet())level.removeBlockEntity(pos);for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),18);originals.clear();}
    }
}
