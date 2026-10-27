package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.storage.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Vanilla final environment damage -> reliable OS channel -> physical endpoint health. */
final class EnvironmentDamageCheck {
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoGravity(true);setSilent(true);getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);setHealth(20);refreshDimensions();}
        @Override protected void registerGoals(){} // ordinary vanilla physics, without wandering goals
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private static final Path PAUSE=Path.of(System.getProperty("sifucraft.motionPauseControl"));
    private volatile boolean finished,nativeOwner,paused;private volatile int stage;
    private volatile long pausedAt;private long pausedGameTime;private float pausedHealth;
    private final boolean live=Boolean.getBoolean("sifucraft.environmentNativeTest");
    private EnemyFrame.Key target=new EnemyFrame.Key(101,1);private double walked;private long lastNs,stableAt;private boolean approachLogged;
    private float playerHealth;
    private long began,deadline;private ServerLevel level;private Reference reference;private Vec3 position;private BlockPos source;
    private float firstLoss,secondLoss;
    EnvironmentDamageCheck(){require(DevWorld.LEVEL_ID.startsWith(live?"sifucraft-environment-native-check-":"sifucraft-environment-check-"),"disposable save required");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("environment damage: "+why);}
    void clientTick(Minecraft mc,SifuFrame frame){
        if(finished){mc.stop();return;}
        nativeOwner=frame!=null&&!frame.minecraftOwnsInput();paused=mc.isPaused();
        var view=WorldMotionWorld.current();long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;
        if(live&&stage==0&&frame!=null&&view!=null&&view.placement().map().equals("Hideout_1_Main")&&mc.player!=null&&!nativeOwner&&view.shapesCurrent()){
            if(playerHealth==0)playerHealth=frame.health();require(frame.health()==playerHealth,"Native player HP unchanged during bounded approach");
            if(view.enemies().entries().isEmpty()&&walked<4){var before=mc.player.position();mc.player.move(net.minecraft.world.entity.MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);}
            else if(!view.enemies().entries().isEmpty()&&!approachLogged){approachLogged=true;LoggerFactory.getLogger("sifucraft").info("[environment-test] live approach READY walked_m={} Native input handoff pending",walked);}
        }
        if(stage==3&&paused&&pausedAt==0){
            var server=mc.getSingleplayerServer();if(server!=null)server.execute(()->{if(pausedAt==0){pausedGameTime=level.getGameTime();pausedHealth=hp();pausedAt=System.currentTimeMillis();}});
        }
        if(stage==3&&pausedAt!=0&&System.currentTimeMillis()-pausedAt>=1000){
            try{Files.writeString(PAUSE,"0");}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    private float hp(){var view=WorldMotionWorld.current();return view==null||view.enemies()==null?-1:view.enemies().entries().stream().filter(e->e.key().equals(target)).findFirst().orElseThrow().health();}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null||(view.nativeStatus().capabilities()&2)==0||!view.placement().map().equals(live?"Hideout_1_Main":"MotionFixture"))return;
            if(stage==6){if(view.shapesCurrent())finished=true;return;}
            if(live&&stage==0){var candidate=view.enemies().entries().stream().filter(e->e.alive()&&e.health()==60&&e.radius()==35&&e.halfHeight()==92).findFirst().orElse(null);if(candidate==null)return;target=candidate.key();}
            var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());if(proxy==null)return;
            if(stage==0){
                if(!nativeOwner||!view.shapesCurrent())return;
                level=server.overworld();began=System.currentTimeMillis();deadline=began+30000;position=proxy.position();source=new BlockPos(proxy.blockPosition().getX(),(int)Math.ceil(proxy.getY()-1e-5),proxy.blockPosition().getZ());
                require(level.getBlockState(source).isAir(),"empty isolated source cell");reference=new Reference(level);reference.setPos(position);require(level.addFreshEntity(reference),"independent ordinary body");
                level.setBlockAndUpdate(source,Blocks.LAVA.defaultBlockState());stage=1;
                LoggerFactory.getLogger("sifucraft").info("[environment-test] actual lava while Native owns input started");return;
            }
            require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" native_HP="+hp()+" reference_HP="+reference.getHealth()+" source="+level.getBlockState(source)+" stats="+dev.sifucraft.combat.WorldEffects.stats());
            reference.setPos(position);reference.setDeltaMovement(Vec3.ZERO);
            if(stage==1){
                if(hp()==60)return;
                firstLoss=20-reference.getHealth();if(firstLoss==0)return;
                require(Math.abs((60-hp())-firstLoss*3)<.001,"first Native loss equals independently resolved vanilla HP delta: native_HP="+hp()+" reference_HP="+reference.getHealth());
                level.setBlockAndUpdate(source,Blocks.WATER.defaultBlockState());stage=2;return;
            }
            if(stage==2){
                if(proxy.isOnFire()||reference.isOnFire()||view.requests().pending()!=0)return;
                require(hp()==60-firstLoss*3,"water extinguishes without duplicate burn damage");
                if(live){
                    if(stableAt==0)stableAt=System.currentTimeMillis();if(System.currentTimeMillis()-stableAt<2000)return;
                    var stats=dev.sifucraft.combat.WorldEffects.stats();require(stats.applied()==1&&stats.rejected()==0&&stats.lost()==0,"one actual Native damage receipt");
                    float after=hp();restore(server);stage=6;
                    AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-native-environment.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","target",target.id(),"spawn",target.spawn(),"mc_final",firstLoss,"native_before",60,"native_after",after,"native_input_owner",true,"water_extinguished",true,"stable_ms",2000),out));
                    LoggerFactory.getLogger("sifucraft").info("[environment-test] PASS actual Native HP 60 -> {}, MC reference loss={}, Native owner, water extinguish/2s no extra effect; {}",after,firstLoss,stats);return;
                }
                if(nativeOwner)return;
                Files.writeString(PAUSE,"1");stage=3;return;
            }
            if(stage==3){
                if(pausedAt==0||paused||System.currentTimeMillis()-pausedAt<1000)return;
                require(level.getGameTime()<=pausedGameTime+1&&hp()==pausedHealth,"Native pause froze world time and health for >=1s");
                // The previous source spread water around the body. Drain that
                // disposable puddle first; otherwise vanilla immediately cools
                // a replacement lava source to obsidian (not another lava test).
                for(var pos:BlockPos.betweenClosed(source.offset(-8,-1,-8),source.offset(8,2,8)))if(level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER))level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(source,Blocks.LAVA.defaultBlockState());stage=4;return;
            }
            if(stage==4){
                if(hp()==pausedHealth)return;
                secondLoss=(20-firstLoss)-reference.getHealth();require(secondLoss>0&&Math.abs((pausedHealth-hp())-secondLoss*3)<.001,"resumed MC ownership uses the same original environment damage result");
                level.setBlockAndUpdate(source,Blocks.WATER.defaultBlockState());stage=5;return;
            }
            if(stage==5){
                if(proxy.isOnFire()||reference.isOnFire()||view.requests().pending()!=0)return;
                var stats=dev.sifucraft.combat.WorldEffects.stats();require(stats.applied()==2&&stats.rejected()==0&&stats.lost()==0,"exactly two reliable effects across F8 and pause: "+stats);
                restore(server);stage=6;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-environment-damage.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","separate process health endpoint, not real Sifu damage","first_mc_loss",firstLoss,"second_mc_loss",secondLoss,"applied",stats.applied(),"native_input_and_mc_input",true,"pause_ms",1000,"paused_world_time",true,"water_extinguishes",true),out));
                LoggerFactory.getLogger("sifucraft").info("[environment-test] PASS real vanilla damage/reference, two OS effects, F8 ownership, water extinguish and 1s true world pause; {}",stats);
            }
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[environment-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){if(reference!=null)reference.discard();reference=null;if(level!=null&&source!=null)level.setBlockAndUpdate(source,Blocks.AIR.defaultBlockState());try{Files.writeString(PAUSE,"0");}catch(Exception e){throw new IllegalStateException(e);}}
}
