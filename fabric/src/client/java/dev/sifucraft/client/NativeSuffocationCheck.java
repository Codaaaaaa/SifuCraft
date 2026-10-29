package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.*;
import dev.sifucraft.combat.*;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/** One original suffocation hit, actual Native HP and an installed living-body control. */
final class NativeSuffocationCheck {
    private static final class Reference extends Cow {
        private EntityDimensions size;
        Reference(ServerLevel level,float width,float height){super(EntityTypes.COW,level);size=EntityDimensions.scalable(width,height);refreshDimensions();setNoAi(true);setNoGravity(true);setSilent(true);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.84f):size;}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile SifuFrame frame;private volatile boolean ready,finished;private volatile AssertionError failure;
    private ServerLevel level;private EnemyFrame.Entry target;private Reference reference;
    private long began,deadline,damageBefore;private int stage,ticks;private float hp,referenceHp,loss;
    NativeSuffocationCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-suffocation-check-"),"disposable world required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("native suffocation: "+why);}
    void clientTick(net.minecraft.client.Minecraft mc,SifuFrame snapshot,boolean anchored){if(failure!=null)throw failure;frame=snapshot;ready=anchored&&snapshot!=null&&snapshot.minecraftOwnsInput();if(finished)mc.stop();}
    void tick(MinecraftServer server){if(finished||failure!=null)return;try{
        if(began==0)began=System.currentTimeMillis();require(System.currentTimeMillis()-began<120000,"bounded fixture readiness");
        var view=WorldMotionWorld.current();if(!ready||view==null||view.nativeStatus()==null||!view.shapesCurrent()||view.nativeStatus().flags()!=0)return;
        if(stage==0){
            level=server.overworld();target=view.enemies().entries().stream().filter(e->e.alive()&&e.maxHealth()==60&&e.health()>=12&&EnemyMirrors.hitAttacker(e.id(),e.spawn())!=null).findFirst().orElse(null);if(target==null)return;
            var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());if(!SceneFluidGeometry.knownVolume(level,proxy.getBoundingBox().inflate(1)))return;
            require(!WorldEffects.canCapture(proxy,proxy.damageSources().inWall()),"bare in_wall has no source");
            hp=frame.health();damageBefore=WorldEffects.stats().applied();
            reference=new Reference(level,proxy.getBbWidth(),proxy.getBbHeight());reference.setPos(proxy.position());reference.getAttribute(Attributes.MAX_HEALTH).setBaseValue(proxy.getMaxHealth());reference.setHealth(proxy.getHealth());referenceHp=reference.getHealth();
            float width=proxy.getBbWidth()*.8f;var eye=AABB.ofSize(proxy.getEyePosition(),width,1e-6,width);
            for(var pos:BlockPos.betweenClosed(eye)){require(level.getBlockState(pos).isAir(),"only empty fixture cells replaced");originals.put(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());}
            deadline=System.currentTimeMillis()+20000;stage=1;return;
        }
        require(frame.health()==hp,"Native player HP unchanged");require(System.currentTimeMillis()<deadline,"bounded stage="+stage+" effects="+WorldEffects.stats());
        var current=view.enemies().entries().stream().filter(e->e.key().equals(target.key())&&e.alive()).findFirst().orElse(null);require(current!=null,"same live activation");
        if(stage==1){if(view.geometry().index()!=WorldBlocks.index().revision()||view.nativeStatus().shapeRevision()!=view.geometry().revision())return;
            require(SuffocationContacts.block(reference)!=null,"original control remains inside the same actual MC block");require(level.addFreshEntity(reference),"register original living control");stage=2;return;}
        if(stage==2){
            if(reference.getHealth()==referenceHp)return;loss=referenceHp-reference.getHealth();require(loss==1,"one original suffocation hit");reference.discard();
            for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),18);stage=3;ticks=0;return;
        }
        if(++ticks<30||view.requests().pending()!=0||view.geometry().index()!=WorldBlocks.index().revision()||view.nativeStatus().shapeRevision()!=view.geometry().revision())return;
        require(WorldEffects.stats().applied()==damageBefore+1&&Math.abs(target.health()-current.health()-loss*3)<.02,"one original hit maps to one actual 3:1 Native loss");
        var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());require(proxy!=null&&proxy.noPhysics&&!proxy.isInWall()&&!WorldEffects.canCapture(proxy,proxy.damageSources().inWall()),"removed source stops damage and mirror physics unchanged");
        AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-native-suffocation.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"target",target.key(),"mc_loss",loss,"native_before",target.health(),"native_after",current.health(),"player_hp",hp,"geometry_ack",view.geometry().revision()),out));
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[native-suffocation-test] PASS one MC hit={} Native {} -> {}, removed source stable, mirror noPhysics retained, cleanup ACK",loss,target.health(),current.health());finished=true;
    }catch(Exception|AssertionError error){restore(server);org.slf4j.LoggerFactory.getLogger("sifucraft").error("[native-suffocation-test] FAIL",error);failure=new AssertionError(error);}}
    void restore(MinecraftServer ignored){if(reference!=null)reference.discard();if(level!=null)for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),18);originals.clear();}
}
