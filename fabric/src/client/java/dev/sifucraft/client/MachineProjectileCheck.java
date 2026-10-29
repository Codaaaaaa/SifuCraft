package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.MachineProjectiles;
import dev.sifucraft.combat.WorldEffects;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.storage.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Real dispenser/arrow/NBT/damage against an independent vanilla Cow and OS endpoint. */
final class MachineProjectileCheck {
    private static final BlockPos DISPENSER=new BlockPos(1,65,0);
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);setHealth(20);refreshDimensions();setPos(24.5,64,.5);}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private volatile boolean finished,nativeOwner;private ServerLevel level;private Reference ref;private Arrow referenceArrow,wallArrow;
    private int stage,phaseTicks;private long began;private Vec3 stuck;private boolean provenance;private float mcLoss;
    private static final Path GATE=Path.of(System.getProperty("sifucraft.fluidGateControl"));
    MachineProjectileCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-machine-projectile-check-"),"disposable save required");MachineProjectiles.observe(this::created);}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("machine projectile: "+why);}
    void clientTick(Minecraft mc,SifuFrame frame){if(finished){mc.stop();return;}nativeOwner=frame!=null&&!frame.minecraftOwnsInput();}
    private Arrow roundtrip(Projectile projectile){
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());projectile.saveWithoutId(output);
        var clone=new Arrow(EntityTypes.ARROW,level);clone.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),output.buildResult()));clone.setUUID(UUID.randomUUID());return clone;
    }
    private void created(Projectile projectile){
        require(ref!=null&&projectile instanceof Arrow,"actual vanilla dispenser arrow");
        var origin=((MachineProjectiles.Carrier)projectile).sifucraft$machineOrigin();require(origin!=null&&origin.pos().equals(DISPENSER),"actual action origin attached before its next tick");
        var clone=roundtrip(projectile);require(origin.equals(((MachineProjectiles.Carrier)clone).sifucraft$machineOrigin()),"real Entity NBT preserved complete machine source");
        var carrier=(MachineProjectiles.Carrier)clone;var target=EnemyMirrors.hitAttacker(101,1);var damage=level.damageSources().arrow(clone,null);
        require(WorldEffects.canCapture(target,damage),"persisted real source has current world authority");
        carrier.sifucraft$machineOrigin(new MachineProjectiles.Origin(origin.action(),origin.save()^1,origin.map(),origin.pos(),origin.state(),origin.revision()));
        require(!WorldEffects.canCapture(target,damage),"different saved world cannot inherit projectile authority");
        carrier.sifucraft$machineOrigin(new MachineProjectiles.Origin(origin.action(),origin.save(),origin.map()^1,origin.pos(),origin.state(),origin.revision()));
        require(!WorldEffects.canCapture(target,damage),"different Native map cannot inherit projectile authority");carrier.sifucraft$machineOrigin(origin);
        clone.setOwner(level.getServer().getPlayerList().getPlayers().getFirst());require(!WorldEffects.canCapture(target,damage),"new projectile owner uses its own combat authority");clone.setOwner(null);
        clone.setPos(projectile.position().add(20,0,0));require(level.addFreshEntity(clone),"independent same initial arrow trajectory");referenceArrow=clone;provenance=true;MachineProjectiles.observe(null);
    }
    private float nativeHp(){return WorldMotionWorld.current().enemies().entries().stream().filter(e->e.id()==101).findFirst().orElseThrow().health();}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            var v=WorldMotionWorld.current();if(v==null||v.nativeStatus()==null||!v.placement().map().equals("MotionFixture")||!v.shapesCurrent())return;
            if(stage==0){
                if(!nativeOwner)return;level=server.overworld();began=System.currentTimeMillis();
                require(SifuCollision.active().solidAt(6.01,65.5,4.5),"Native dynamic gate closed");
                ref=new Reference(level);require(level.addFreshEntity(ref),"independent target");
                var unknown=new Arrow(EntityTypes.ARROW,level);var target=EnemyMirrors.hitAttacker(102,1);
                require(target!=null&&!target.hurtServer(level,level.damageSources().arrow(unknown,null),2),"unknown ownerless arrow has no machine authority");
                level.setBlockAndUpdate(DISPENSER,Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST));
                var be=(DispenserBlockEntity)level.getBlockEntity(DISPENSER);be.setItem(0,new ItemStack(Items.ARROW));
                level.setBlockAndUpdate(DISPENSER.above(),Blocks.REDSTONE_BLOCK.defaultBlockState());stage=1;return;
            }
            require(System.currentTimeMillis()-began<20000,"bounded fixture stage="+stage+" nativeHP="+nativeHp()+" reference="+(ref==null?0:ref.getHealth())+" stats="+WorldEffects.stats());
            if(stage==1){
                if(nativeHp()==60||ref.getHealth()==20||WorldEffects.stats().applied()==0)return;
                mcLoss=20-ref.getHealth();require(provenance&&Math.abs(60-nativeHp()-mcLoss*3)<1e-5,"actual OS damage equals independently hit Cow final HP delta");
                require(((DispenserBlockEntity)level.getBlockEntity(DISPENSER)).getItem(0).isEmpty(),"one actual dispenser item consumed");
                require(WorldEffects.stats().applied()==1&&WorldEffects.stats().sent()==1,"one reliable world effect while Native owns input");
                wallArrow=new Arrow(EntityTypes.ARROW,level);wallArrow.setPos(4.5,65.5,4.5);wallArrow.setDeltaMovement(1,0,0);require(level.addFreshEntity(wallArrow),"separate wall-ray control");stage=2;phaseTicks=0;return;
            }
            if(stage==2){
                if(++phaseTicks<8)return;require(wallArrow.position().x<6&&wallArrow.getDeltaMovement().lengthSqr()==0,"actual arrow stopped at imported face before corridor");stuck=wallArrow.position();
                var restored=roundtrip(wallArrow);wallArrow.discard();wallArrow=restored;require(level.addFreshEntity(restored),"saved Native-face arrow restored as a new life");stage=3;phaseTicks=0;return;
            }
            if(stage==3){
                if(++phaseTicks<6)return;require(wallArrow.position().distanceTo(stuck)<1e-8,"Native anchor survives actual entity save/load");Files.writeString(GATE,"0");stage=4;phaseTicks=0;return;
            }
            if(stage==4){
                if(SifuCollision.active().solidAt(6.01,65.5,4.5))return;if(++phaseTicks<6)return;
                require(wallArrow.getY()<stuck.y-.2,"opened Native gate releases saved arrow through vanilla falling");
                float after=nativeHp();restore(server);stage=5;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-machine-projectile.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","OS AABB health endpoint, not real Sifu","native_input_owner_at_launch",true,"mc_final",mcLoss,"native_hp_after",after,"provenance_nbt",true,"unknown_arrow_rejected",true,"native_gate_anchor_nbt_and_release",true),out));
                LoggerFactory.getLogger("sifucraft").info("[machine-projectile-test] PASS actual dispenser/inventory/independent vanilla arrow HP/NBT/Native gate anchor+release, mc_loss={} native_HP={}",mcLoss,after);return;
            }
            if(stage==5)finished=true;
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[machine-projectile-test] FAIL",e);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){MachineProjectiles.observe(null);if(ref!=null)ref.discard();if(referenceArrow!=null)referenceArrow.discard();if(wallArrow!=null)wallArrow.discard();if(level!=null){level.removeBlock(DISPENSER.above(),false);level.removeBlock(DISPENSER,false);}try{Files.writeString(GATE,"1");}catch(Exception e){throw new IllegalStateException(e);}}
}
