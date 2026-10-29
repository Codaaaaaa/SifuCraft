package dev.sifucraft.combat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Optional Create belts, found reflectively: without Create both calls are inert.
 * The original BeltMovementHandler moves the flight body inside its prediction. */
public final class CreateBelts {
    private static final Class<?> BELT;
    private static final MethodHandle SPEED,TRANSPORT,INFO;
    static {
        Class<?> belt=null;MethodHandle speed=null,transport=null,info=null;
        try{
            var lookup=MethodHandles.publicLookup();
            belt=Class.forName("com.zurrtum.create.content.kinetics.belt.BeltBlockEntity");
            var handler=Class.forName("com.zurrtum.create.content.kinetics.belt.transport.BeltMovementHandler");
            var infoType=Class.forName("com.zurrtum.create.content.kinetics.belt.transport.BeltMovementHandler$TransportedEntityInfo");
            speed=lookup.findVirtual(belt,"getSpeed",MethodType.methodType(float.class));
            transport=lookup.findStatic(handler,"transportEntity",MethodType.methodType(void.class,belt,Entity.class,infoType));
            info=lookup.findConstructor(infoType,MethodType.methodType(void.class,BlockPos.class,BlockState.class));
        }catch(ReflectiveOperationException|LinkageError absent){belt=null;}
        BELT=belt;SPEED=speed;TRANSPORT=transport;INFO=info;
    }
    private CreateBelts(){}
    public static boolean available(){return BELT!=null;}

    /** A running belt directly under these feet (flat or sloped), or null. */
    static BlockPos moving(Level level,AABB box){
        if(BELT==null)return null;
        var c=box.getCenter();var below=BlockPos.containing(c.x,box.minY-.05,c.z);
        for(var pos:new BlockPos[]{below,below.above()}){
            if(!level.hasChunkAt(pos))continue;
            var be=level.getBlockEntity(pos);
            try{if(BELT.isInstance(be)&&(float)SPEED.invoke(be)!=0)return pos.immutable();}
            catch(Throwable error){return null;}
        }
        return null;
    }
    static void transport(Entity body){
        var pos=moving(body.level(),body.getBoundingBox());if(pos==null)return;
        try{TRANSPORT.invoke(body.level().getBlockEntity(pos),body,INFO.invoke(pos,body.level().getBlockState(pos)));}
        catch(Throwable error){org.slf4j.LoggerFactory.getLogger("sifucraft").warn("[create] belt transport failed",error);}
    }
}
