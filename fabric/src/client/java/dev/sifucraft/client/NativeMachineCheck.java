package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.MachineProjectiles;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.storage.AtomicFiles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** An actual redstone-fired dispenser hits a real Native NPC, independently compared with vanilla HP. */
final class NativeMachineCheck {
    private final boolean effects=Boolean.getBoolean("sifucraft.nativeMachineEffects");
    private Projectile referenceProduct;
    private Projectile actualProduct;
    private boolean controlledImpact;
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);setHealth(20);refreshDimensions();}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private final Map<BlockPos,BlockState> original=new LinkedHashMap<>();
    private volatile boolean finished,nativeOwner,ready;private long lastNs,deadline,settled;private double walked;private float playerHp;
    private int stage;private ServerLevel level;private EnemyFrame.Entry target;private BlockPos base;private Reference reference;private Arrow referenceArrow;private boolean originSaved;private boolean logged;
    NativeMachineCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-machine-native-check-"),"disposable save required");MachineProjectiles.observe(this::created);}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("native machine: "+why);}
    void clientTick(Minecraft mc,SifuFrame frame){
        if(finished){mc.stop();return;}var v=WorldMotionWorld.current();nativeOwner=frame!=null&&!frame.minecraftOwnsInput();
        ready=frame!=null&&v!=null&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&v.shapesCurrent()&&(v.nativeStatus().capabilities()&2)!=0&&v.placement().map().equals("Hideout_1_Main");
        long ns=System.nanoTime();double dt=lastNs==0?0:Math.min(.05,(ns-lastNs)/1e9);lastNs=ns;if(!ready||mc.player==null)return;
        if(playerHp==0)playerHp=frame.health();require(frame.health()==playerHp,"Native player HP preserved");
        if(stage==0&&!nativeOwner&&(effects||v.enemies().entries().isEmpty())&&walked<4){var before=mc.player.position();mc.player.move(MoverType.SELF,new Vec3(.6*dt,0,.8*dt));walked+=mc.player.position().distanceTo(before);}
        else if(stage==0&&!v.enemies().entries().isEmpty()&&(!effects||walked>=4)&&!logged){logged=true;LoggerFactory.getLogger("sifucraft").info("[native-machine-test] approach READY {}m, Native handoff pending",walked);}
    }
    private void set(BlockPos pos,BlockState state){original.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void created(Projectile projectile){
        if(effects){
            require(projectile instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion&&reference!=null,"real dispenser poison splash");
            var origin=((MachineProjectiles.Carrier)projectile).sifucraft$machineOrigin();
            var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());projectile.saveWithoutId(out);
            var clone=(Projectile)projectile.getType().create(level,net.minecraft.world.entity.EntitySpawnReason.LOAD);require(clone!=null,"original poison projectile loader");
            clone.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),out.buildResult()));clone.setUUID(UUID.randomUUID());
            require(origin!=null&&origin.equals(((MachineProjectiles.Carrier)clone).sifucraft$machineOrigin()),"poison source NBT");originSaved=true;
            clone.setPos(projectile.position().add(20,200,0));require(level.addFreshEntity(clone),"independent same original splash above Native geometry");actualProduct=projectile;referenceProduct=clone;MachineProjectiles.observe(null);return;
        }
        require(projectile instanceof Arrow&&reference!=null,"real dispenser arrow");var origin=((MachineProjectiles.Carrier)projectile).sifucraft$machineOrigin();
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());projectile.saveWithoutId(out);var clone=new Arrow(EntityTypes.ARROW,level);
        clone.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),out.buildResult()));clone.setUUID(UUID.randomUUID());
        require(origin!=null&&origin.equals(((MachineProjectiles.Carrier)clone).sifucraft$machineOrigin()),"real projectile source NBT roundtrip");originSaved=true;
        clone.setPos(projectile.position().add(20,200,0));require(level.addFreshEntity(clone),"independent same original arrow above all Native geometry");referenceArrow=clone;MachineProjectiles.observe(null);
    }
    void tick(MinecraftServer server){
        if(finished||!ready)return;
        try{
            var v=WorldMotionWorld.current();if(v==null)return;
            if(stage==0){
                if(!nativeOwner||effects&&walked<4)return;level=server.overworld();
                for(var e:v.enemies().entries())if(e.alive()&&e.maxHealth()==60&&e.radius()==35&&e.halfHeight()==92){
                    var proxy=EnemyMirrors.hitAttacker(e.id(),e.spawn());if(proxy==null)continue;
                    var pos=new BlockPos(proxy.blockPosition().getX()-3,(int)Math.ceil(proxy.getY()-1e-5)+1,proxy.blockPosition().getZ());boolean clear=true;
                    for(int x=0;x<=3;x++){var trial=pos.east(x);if(!level.getBlockState(trial).isAir()||!SceneBlockSupport.nativeShape(level,trial).isEmpty()){clear=false;break;}}
                    if(!clear)continue;target=e;base=pos;deadline=System.currentTimeMillis()+20000;
                    reference=new Reference(level);reference.setPos(proxy.position().add(20,200,0));require(level.addFreshEntity(reference),"independent ordinary Cow hit target");
                    set(base,Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST));
                    var item=new ItemStack(effects?Items.SPLASH_POTION:Items.ARROW);
                    if(effects)item.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,net.minecraft.world.item.alchemy.PotionContents.EMPTY.withEffectAdded(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON,180,0)));
                    ((DispenserBlockEntity)level.getBlockEntity(base)).setItem(0,item);stage=1;
                    LoggerFactory.getLogger("sifucraft").info("[native-machine-test] prepared real dispenser {} -> target={}/{} HP={}",base,target.id(),target.spawn(),target.health());return;
                }
                return;
            }
            if(stage==5){if(v.shapesCurrent())finished=true;return;}
            require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" stats="+WorldEffects.stats());
            if(stage==1){set(base.above(),Blocks.REDSTONE_BLOCK.defaultBlockState());stage=2;return;}
            var current=v.enemies().entries().stream().filter(e->e.key().equals(target.key())).findFirst().orElse(null);
            if(current==null)return; // A transient unavailable target is not a new activation or an HP receipt.
            if(stage==2){
                if(effects){
                    if(!controlledImpact&&actualProduct!=null){
                        var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());if(proxy==null)return;
                        // Isolate periodic-effect application from random splash flight. Both
                        // actual dispenser products execute their original impact handler at
                        // the same distance to their registered targets. This is not a trajectory test.
                        actualProduct.setPos(proxy.position().add(0,1,0));referenceProduct.setPos(reference.position().add(0,1,0));
                        ((net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion)actualProduct).onHitAsPotion(level,((net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion)actualProduct).getItem(),net.minecraft.world.phys.BlockHitResult.miss(actualProduct.position(),Direction.UP,base));
                        ((net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion)referenceProduct).onHitAsPotion(level,((net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion)referenceProduct).getItem(),net.minecraft.world.phys.BlockHitResult.miss(referenceProduct.position(),Direction.UP,base));
                        actualProduct.discard();referenceProduct.discard();controlledImpact=true;
                        require(proxy.hasEffect(net.minecraft.world.effect.MobEffects.POISON)&&reference.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"controlled original impact applied poison to both targets");
                        LoggerFactory.getLogger("sifucraft").info("[native-machine-test] controlled original splash impact; periodic HP comparison started");
                    }
                    if(WorldEffects.stats().applied()<3)return;
                    var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());
                    if(proxy==null)return;
                    require(proxy!=null&&reference.hasEffect(net.minecraft.world.effect.MobEffects.POISON)&&proxy.hasEffect(net.minecraft.world.effect.MobEffects.POISON),"both actual splash targets have original poison");
                    proxy.removeAllEffects();reference.removeAllEffects();settled=System.currentTimeMillis();stage=3;return;
                }
                if(current.health()==target.health()||reference.getHealth()==20||WorldEffects.stats().applied()==0)return;
                float loss=20-reference.getHealth();require(originSaved&&Math.abs(target.health()-current.health()-loss*3)<1e-4,"Native actual HP delta matches original independently hit Cow");
                require(((DispenserBlockEntity)level.getBlockEntity(base)).getItem(0).isEmpty()&&WorldEffects.stats().sent()==1&&WorldEffects.stats().applied()==1&&WorldEffects.stats().rejected()==0,"one inventory item/one actual H7 effect");
                settled=System.currentTimeMillis();stage=3;return;
            }
            if(stage==3){
                if(System.currentTimeMillis()-settled<2000)return;float loss=20-reference.getHealth();require(Math.abs(target.health()-current.health()-loss*3)<1e-4,"no duplicate Native damage");
                require(!effects||originSaved&&WorldEffects.stats().sent()==WorldEffects.stats().applied()&&WorldEffects.stats().rejected()==0&&WorldEffects.stats().applied()>=3&&((DispenserBlockEntity)level.getBlockEntity(base)).getItem(0).isEmpty(),"poison inventory and reliable receipts");
                float after=current.health();restore(server);stage=5;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-native-machine.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","target",target.id(),"spawn",target.spawn(),"native_before",target.health(),"native_after",after,"mc_final",loss,"native_owner",true,"inventory_consumed",1,"origin_nbt",true,"stable_ms",2000),out));
                LoggerFactory.getLogger("sifucraft").info("[native-machine-test] PASS actual dispenser {} real Native HP {} -> {}, independent MC final={}, one inventory, H7={}, Native owner",effects?"periodic poison":"arrow",target.health(),after,loss,WorldEffects.stats().applied());
            }
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[native-machine-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){MachineProjectiles.observe(null);if(effects&&target!=null){var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());if(proxy!=null)proxy.removeAllEffects();}if(reference!=null)reference.discard();if(referenceProduct!=null)referenceProduct.discard();if(referenceArrow!=null)referenceArrow.discard();if(level!=null)for(var e:original.entrySet())level.setBlockAndUpdate(e.getKey(),e.getValue());original.clear();}
}
