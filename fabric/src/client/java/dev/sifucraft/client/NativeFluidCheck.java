package dev.sifucraft.client;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.slf4j.LoggerFactory;

/** Disposable real NPC water -> upward bubble -> downward bubble -> safe floor release. */
final class NativeFluidCheck {
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean ready,finished;private volatile int stage;
    private long lastNs,began,deadline,phaseTick,settled;private double walked,startY,waterY,upY,peakY,downY;private float playerHp;
    private ServerLevel level;private EnemyFrame.Entry target;private BlockPos base;
    NativeFluidCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-native-check-"),"disposable save required");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("native fluid: "+why);}
    void clientTick(Minecraft mc,SifuFrame frame){
        if(finished){mc.stop();return;}
        var v=WorldMotionWorld.current();ready=frame!=null&&v!=null&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&v.shapesCurrent()&&(v.nativeStatus().capabilities()&1)!=0&&v.placement().map().equals("Hideout_1_Main");
        long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;
        if(!ready||mc.player==null||!frame.minecraftOwnsInput())return;
        if(began==0){began=System.currentTimeMillis();playerHp=frame.health();}require(frame.health()==playerHp,"Native player HP unchanged");
        require(System.currentTimeMillis()-began<90000,"bounded scene deadline stage="+stage);
        if(stage==0&&v.enemies().entries().isEmpty()&&walked<4){var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);}
    }
    private void set(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"no saved BE replaced");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void drain(){for(var pos:BlockPos.betweenClosed(base.offset(-12,-1,-12),base.offset(12,14,12)))if(!level.getFluidState(pos).isEmpty())level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());}
    void tick(MinecraftServer server){
        if(finished||!ready)return;
        try{
            var v=WorldMotionWorld.current();if(v==null)return;
            if(stage==0){
                level=server.overworld();
                for(var candidate:v.enemies().entries())if(candidate.alive()&&candidate.maxHealth()==60&&candidate.radius()==35&&candidate.halfHeight()==92){
                    var proxy=EnemyMirrors.hitAttacker(candidate.id(),candidate.spawn());if(proxy==null)continue;
                    var origin=new BlockPos(proxy.blockPosition().getX(),(int)Math.ceil(proxy.getY()-1e-5),proxy.blockPosition().getZ());boolean clear=true;
                    for(var pos:BlockPos.betweenClosed(origin.offset(-1,0,-1),origin.offset(1,11,1)))if(!level.getBlockState(pos).isAir()||!SceneBlockSupport.nativeShape(level,pos).isEmpty()){clear=false;break;}
                    if(!clear)continue;
                    target=candidate;base=origin;startY=proxy.getY();deadline=System.currentTimeMillis()+45000;
                    for(var pos:BlockPos.betweenClosed(base.offset(-1,0,-1),base.offset(1,11,1)))set(pos,Blocks.WATER.defaultBlockState());
                    stage=1;LoggerFactory.getLogger("sifucraft").info("[native-fluid-test] water target={}/{} HP={} base={} start_y={} approached={}m",target.id(),target.spawn(),target.health(),base,startY,walked);return;
                }
                return;
            }
            if(stage==7){if(v.shapesCurrent())finished=true;return;}
            require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" stats="+FluidMotion.stats());
            var current=v.enemies().entries().stream().filter(e->e.key().equals(target.key())).findFirst().orElseThrow();
            require(current.health()==target.health(),"real Native HP unchanged during water/bubble fixture");
            double feet=v.placement().mcY(current.location().z()-current.halfHeight());
            if(stage==1){
                require(FluidMotion.stats().failed()==0,"initial fluid lease qualifies without restarting: "+FluidMotion.stats());
                if(feet-startY<1.3)return;waterY=feet;set(base,Blocks.SOUL_SAND.defaultBlockState());phaseTick=level.getGameTime();stage=2;
                LoggerFactory.getLogger("sifucraft").info("[native-fluid-test] ordinary water rose {}m; soul-sand base placed below actual body",feet-startY);return;
            }
            var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());require(proxy!=null,"same live hit mirror");var bodyCell=proxy.blockPosition();var state=level.getBlockState(bodyCell);
            if(stage==2){
                if(!state.is(Blocks.BUBBLE_COLUMN)||state.getValue(BubbleColumnBlock.DRAG_DOWN))return;
                if(feet-waterY<1||level.getGameTime()-phaseTick<15)return;
                upY=feet;peakY=feet;set(base,Blocks.MAGMA_BLOCK.defaultBlockState());stage=3;phaseTick=level.getGameTime();
                LoggerFactory.getLogger("sifucraft").info("[native-fluid-test] real upward bubble {}m; changed base to magma",feet-waterY);return;
            }
            if(stage==3){
                peakY=Math.max(peakY,feet);if(!state.is(Blocks.BUBBLE_COLUMN)||!state.getValue(BubbleColumnBlock.DRAG_DOWN)||peakY-feet<.35)return;
                downY=feet;set(base,Blocks.AIR.defaultBlockState());drain();stage=4;
                LoggerFactory.getLogger("sifucraft").info("[native-fluid-test] real downward bubble {}m; drained medium and removed base while safely above floor",peakY-feet);return;
            }
            if(stage==4){
                if(FluidMotion.stats().active()!=0)return;
                require(FluidMotion.stats().released()==1&&FluidMotion.stats().failed()==0,"one renewed lease restored its three originals after actual landing: "+FluidMotion.stats());
                require(Math.abs(feet-startY)<.06,"returned to original Native floor, no hover: delta="+(feet-startY));settled=System.currentTimeMillis();stage=5;return;
            }
            if(stage==5){
                if(System.currentTimeMillis()-settled<3000)return;require(Math.abs(feet-startY)<.06,"three seconds no vertical pullback/drift");
                var stats=FluidMotion.stats();restore(server);stage=7;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-native-fluid.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","target",target.id(),"spawn",target.spawn(),"water_rise_m",waterY-startY,"up_bubble_rise_m",upY-waterY,"down_bubble_drop_m",peakY-downY,"native_health",target.health(),"stable_ms",3000,"stats",stats),out));
                LoggerFactory.getLogger("sifucraft").info("[native-fluid-test] PASS actual water/up/down bubble and safe landing/three originals/HP/3s; {}",stats);
            }
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[native-fluid-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){if(level!=null&&base!=null){drain();for(var e:originals.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());originals.clear();}}
}
