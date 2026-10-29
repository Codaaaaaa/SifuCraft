package dev.sifucraft.combat;

import dev.sifucraft.collision.CollisionGrid;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class P10GeometryTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static CollisionGrid grid(int axis,boolean gap)throws Exception{
        byte[] name="ExplosionFixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var b=ByteBuffer.allocate(36+name.length+76).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8).putFloat(0).putFloat(0).putFloat(0).putInt(name.length).put(name).putInt(1).putInt(0).putInt(0).putInt(0);
        long[] layers=new long[8];
        for(int x=0;x<8;x++)for(int y=0;y<8;y++)for(int z=0;z<8;z++)
            if((axis==0?x:axis==1?y:z)==4 && (!gap || (axis==0?z:x)!=4 || (axis==1?z:y)!=4))layers[y]|=1L<<(x+8*z);
        for(long bits:layers)b.putLong(bits);
        var path=Path.of("build/reports/p10/wall-"+axis+"-"+gap+".scvx");Files.createDirectories(path.getParent());Files.write(path,b.array());return CollisionGrid.read(path);
    }
    public static void main(String[] args)throws Exception{
        for(int axis=0;axis<3;axis++){
            var placement=new MapPlacement("ExplosionFixture",2048,64,-2048);
            var center=new Vec3(2048.5625,64.5625,-2047.4375);
            var d=axis==0?new Vec3(1,0,0):axis==1?new Vec3(0,1,0):new Vec3(0,0,1);
            var from=center.subtract(d.scale(.2));var to=center.add(d.scale(.1));
            var scene=new SifuCollision.Active(grid(axis,false),placement);
            check(!scene.solidAt(from.x,from.y,from.z)&&!scene.solidAt(to.x,to.y,to.z),"0.3m step endpoints miss 1/8m wall");
            check(SceneExplosions.stopsDestructiveRay(scene,from.subtract(d),to.x,to.y,to.z,d.x,d.y,d.z),"swept explosion ray catches thin wall axis="+axis);
            check(SceneExplosions.blocked(scene,center,from),"exposure origin inside solid is blocked");
            var miss=BlockHitResult.miss(to,Direction.UP,BlockPos.containing(to));
            check(SceneExplosions.exposureHit(scene,from,to,miss).getType()==HitResult.Type.BLOCK,"native wall blocks vanilla MISS");
            var nearer=new BlockHitResult(from,Direction.UP,BlockPos.containing(from),false);
            check(SceneExplosions.exposureHit(scene,from,to,nearer)==nearer,"nearer vanilla collision retained");
            var open=new SifuCollision.Active(grid(axis,true),placement);
            check(!SceneExplosions.blocked(open,from,to),"real 1/8m aperture remains open");
            var shut=new SifuCollision.Active(grid(axis,true),placement,grid(axis,false));
            check(SceneExplosions.blocked(shut,from,to),"dynamic gate closes aperture");
            SifuCollision.set(open);
            check(SifuCollision.withQueryView(shut,()->{
                check(SifuCollision.queryView()==shut,"pinned explosion geometry");
                SifuCollision.withQueryView(open,()->{check(SifuCollision.queryView()==open,"nested explosion geometry");return true;});
                return SifuCollision.queryView()==shut;
            }),"outer explosion view restored after nested call");
            check(SifuCollision.queryView()==open,"explosion view released");
        }
        SifuCollision.set(null);
        System.out.println("P10 geometry PASS "+checks+" cases: three-axis thin-wall sweep, apertures, dynamic gates, translated map, nested geometry scope");
    }
}
