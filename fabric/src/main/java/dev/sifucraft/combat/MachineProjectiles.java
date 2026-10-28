package dev.sifucraft.combat;

import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.ShapeProtocol;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Provenance of an actual vanilla dispenser operation, preserved on its projectile. */
public final class MachineProjectiles {
    private MachineProjectiles(){}
    public record Origin(UUID action,long save,long map,BlockPos pos,long state,long revision){}
    public interface Carrier {Origin sifucraft$machineOrigin();void sifucraft$machineOrigin(Origin origin);}
    private static final ThreadLocal<Origin> dispensing=new ThreadLocal<>();
    private static volatile java.util.function.Consumer<Projectile> observer;
    public static void observe(java.util.function.Consumer<Projectile> value){if(value!=null&&!Boolean.getBoolean("sifucraft.machineProjectileSelfTest")&&!Boolean.getBoolean("sifucraft.nativeMachineSelfTest")&&!Boolean.getBoolean("sifucraft.machineSpecialSelfTest")&&!Boolean.getBoolean("sifucraft.machineEffectsSelfTest"))throw new IllegalStateException("isolated fixture only");observer=value;}
    public static void dispense(ServerLevel level,BlockPos pos,Runnable original){
        var previous=dispensing.get();var v=WorldMotionWorld.current();Origin origin=null;
        if(WorldBlocks.ENABLED&&level.dimension()==Level.OVERWORLD&&level.getServer().isSameThread()&&v!=null&&v.placement().containsX(pos.getX())&&v.scope().save()==PistonMovePlans.saveKey(level)&&
            level.getBlockState(pos).is(Blocks.DISPENSER)&&level.getBlockEntity(pos) instanceof DispenserBlockEntity){
            var section=WorldBlocks.index().snapshot(SectionPos.of(pos).asLong());long state=ShapeProtocol.mapHash(BlockStateKey.encode(level.getBlockState(pos)));
            if(section!=null)origin=new Origin(UUID.randomUUID(),v.scope().save(),v.scope().map(),pos.immutable(),state==0?1:state,section.revision());
        }
        if(origin==null)dispensing.remove();else dispensing.set(origin);
        try{original.run();}finally{if(previous==null)dispensing.remove();else dispensing.set(previous);}
    }
    public static void created(Projectile projectile){
        var origin=dispensing.get();if(origin!=null&&projectile.getOwner()==null&&projectile instanceof Carrier carrier&&carrier.sifucraft$machineOrigin()==null){carrier.sifucraft$machineOrigin(origin);var listener=observer;if(listener!=null)listener.accept(projectile);}
    }
    public static Origin evidence(EnemyProxy target,DamageSource source){
        var direct=source.getDirectEntity();
        if(direct==null||direct.level()!=target.level()||!(direct instanceof Carrier carrier)||!unowned(direct))return null;
        var origin=carrier.sifucraft$machineOrigin();var view=WorldMotionWorld.current();
        if(origin==null||view==null||origin.save!=view.scope().save()||origin.map!=view.scope().map())return null;
        if(source.getEntity()!=null&&source.getEntity()!=direct)return null;
        String type=source.typeHolder().unwrapKey().orElseThrow().identifier().toString();
        return switch(type){
            case "minecraft:arrow","minecraft:trident","minecraft:thrown","minecraft:fireball","minecraft:unattributed_fireball"->direct instanceof Projectile?origin:null;
            case "minecraft:fireworks"->direct instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity?origin:null;
            case "minecraft:indirect_magic"->direct instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion||direct instanceof AreaEffectCloud?origin:null;
            default->null;
        };
    }
    private static boolean unowned(Entity entity){return entity instanceof Projectile projectile?projectile.getOwner()==null:entity instanceof AreaEffectCloud cloud&&cloud.getOwner()==null;}
    static Origin effectOrigin(Entity source,EnemyProxy target){
        if(source==null||source.level()!=target.level()||!(source instanceof Carrier carrier)||!unowned(source))return null;
        var origin=carrier.sifucraft$machineOrigin();var view=WorldMotionWorld.current();
        return origin!=null&&view!=null&&origin.save==view.scope().save()&&origin.map==view.scope().map()?origin:null;
    }
    /** The original lingering potion creates exactly one cloud; retain its machine credential. */
    public static void cloud(Projectile potion,Entity cloud){
        if(potion.getOwner()==null&&potion instanceof Carrier from&&cloud instanceof AreaEffectCloud&&cloud instanceof Carrier to)
            to.sifucraft$machineOrigin(from.sifucraft$machineOrigin());
    }
    public static void save(Origin origin,ValueOutput output){
        if(origin==null)return;var out=output.child("sifucraft_machine_origin");out.putLong("hi",origin.action.getMostSignificantBits());out.putLong("lo",origin.action.getLeastSignificantBits());
        out.putLong("save",origin.save);out.putLong("map",origin.map);out.putLong("block",origin.pos.asLong());out.putLong("state",origin.state);out.putLong("revision",origin.revision);
    }
    public static Origin load(ValueInput input){
        var in=input.childOrEmpty("sifucraft_machine_origin");long hi=in.getLongOr("hi",0),lo=in.getLongOr("lo",0),save=in.getLongOr("save",0),map=in.getLongOr("map",0),state=in.getLongOr("state",0),revision=in.getLongOr("revision",0);
        return (hi|lo)==0||save==0||map==0||state==0||revision<=0?null:new Origin(new UUID(hi,lo),save,map,BlockPos.of(in.getLongOr("block",0)),state,revision);
    }
}
