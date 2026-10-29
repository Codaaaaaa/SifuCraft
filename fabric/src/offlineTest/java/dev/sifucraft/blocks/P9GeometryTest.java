package dev.sifucraft.blocks;

import dev.sifucraft.collision.CollisionGrid;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;

/** Known spatial cases, not a second flood-fill implementation used as an oracle. */
public final class P9GeometryTest {
    private static int checks,sequence;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void solid(long[] layers,int x,int y,int z){layers[y]|=1L<<(x+8*z);}
    private static CollisionGrid grid(long[] layers)throws Exception{
        byte[] name="fluid-geometry".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var b=ByteBuffer.allocate(40+name.length+76).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8).putFloat(0).putFloat(0).putFloat(0).putInt(name.length).put(name).putInt(1);
        b.putInt(0).putInt(0).putInt(0);for(long bits:layers)b.putLong(bits);
        Path path=Path.of("build/reports/p9/geometry/"+(sequence++)+".scvx");Files.createDirectories(path.getParent());Files.write(path,java.util.Arrays.copyOf(b.array(),b.position()));return CollisionGrid.read(path);
    }
    private static SifuCollision.Active scene(long[] layers)throws Exception{return new SifuCollision.Active(grid(layers),new MapPlacement("fluid-geometry",0,0,0));}
    public static void main(String[] args)throws Exception{
        check(scene(new long[8]).fluidCell(BlockPos.ZERO),"empty cell accepts liquid");
        long[] full=new long[8];java.util.Arrays.fill(full,-1L);check(!scene(full).fluidCell(BlockPos.ZERO),"solid Native cell rejects liquid");
        for(int axis=0;axis<3;axis++)for(int at=0;at<8;at++){
            long[] wall=new long[8];for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++)if((axis==0?x:axis==1?y:z)==at)solid(wall,x,y,z);
            check(scene(wall).fluidCell(BlockPos.ZERO)==(at==0||at==7),"boundary slabs leave one pocket; internal 1/8 wall separates two pockets axis="+axis+" at="+at);
            int x=axis==0?at:4,y=axis==1?at:4,z=axis==2?at:4;wall[y]&=~(1L<<(x+8*z));
            check(scene(wall).fluidCell(BlockPos.ZERO),"a genuine 1/8 opening reconnects the empty volume axis="+axis+" at="+at);
        }
        long[] separated=full.clone();separated[0]&=~1L;separated[7]&=~(1L<<63);
        check(!scene(separated).fluidCell(BlockPos.ZERO),"diagonal pockets do not communicate");
        separated=full.clone();separated[0]&=~3L;check(scene(separated).fluidCell(BlockPos.ZERO),"face-adjacent pockets communicate");
        long[] wall=new long[8];for(int y=0;y<8;y++)for(int z=0;z<8;z++)solid(wall,4,y,z);wall[4]&=~(1L<<(4+8*4));
        long[] door=new long[8];solid(door,4,4,4);var fixed=grid(wall);var placement=new MapPlacement("fluid-geometry",2048,64,-2048);var at=new BlockPos(2048,64,-2048);
        var open=new SifuCollision.Active(fixed,placement,grid(new long[8]),11,2);check(open.fluidCell(at),"translated open gate");
        var closed=new SifuCollision.Active(fixed,placement,grid(door),11,2);check(!closed.fluidCell(at),"static and Native door union closes the aperture");
        check(open.fluidCell(at)&&!closed.fluidCell(at),"geometry caches are immutable and isolated by scene revision");
        check(closed.fluidCell(at.east()),"solid lookup does not bleed across coordinates");
        System.out.println("P9 geometry PASS "+checks+" spatial cases: solid/floor/ceiling/three-axis thin walls/real apertures/door union/revision/map placement");
    }
}
