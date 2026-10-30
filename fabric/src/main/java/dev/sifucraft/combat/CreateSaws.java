package dev.sifucraft.combat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.AABB;

/** Optional Create saws, found reflectively: proof that a running saw touched a Sifu NPC's proxy. */
public final class CreateSaws {
    private static final Class<?> SAW;
    private static final MethodHandle SPEED;
    static {
        Class<?> saw=null;MethodHandle speed=null;
        try{
            saw=Class.forName("com.zurrtum.create.content.kinetics.saw.SawBlockEntity");
            speed=MethodHandles.publicLookup().findVirtual(saw,"getSpeed",MethodType.methodType(float.class));
        }catch(ReflectiveOperationException|LinkageError absent){saw=null;}
        SAW=saw;SPEED=speed;
    }
    private CreateSaws(){}
    private static final ThreadLocal<BlockPos> moving=new ThreadLocal<>();
    /** Set by the mixin around a contraption saw's own damageEntities(pos) call. */
    public static void contraption(BlockPos pos){if(pos==null)moving.remove();else moving.set(pos.immutable());}

    /** The saw block that hurt this proxy with Create's saw damage, or null. */
    static BlockPos contact(EnemyProxy proxy,DamageSource source){
        if(SAW==null||!source.typeHolder().unwrapKey().map(k->k.identifier().toString().equals("create:saw")).orElse(false))return null;
        var box=proxy.getBoundingBox();
        var pos=moving.get(); // damageEntities hurts exactly the entities inside new AABB(pos)
        if(pos!=null)return box.intersects(new AABB(pos))?pos:null;
        var level=proxy.level();var area=box.inflate(.05);
        for(var at:BlockPos.betweenClosed(BlockPos.containing(area.minX,area.minY,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))){
            if(!level.hasChunkAt(at))continue;var be=level.getBlockEntity(at);
            try{if(SAW.isInstance(be)&&(float)SPEED.invoke(be)!=0)return at.immutable();}
            catch(Throwable error){return null;}
        }
        return null;
    }
}
