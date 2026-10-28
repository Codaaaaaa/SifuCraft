package dev.sifucraft.collision;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Shared native/Java wire fixture plus known-air/unknown and malformed metadata. */
public final class CoverageFileTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void rejected(Path path,byte[] bytes)throws Exception{
        Files.write(path,bytes);try{CollisionGrid.read(path);throw new AssertionError("malformed coverage accepted");}catch(java.io.IOException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        byte[] bytes=HexFormat.of().parseHex(Files.readString(Path.of(args[0])).replaceAll("\\s+",""));
        var path=Path.of("build/reports/p9/coverage-wire.scvx");Files.createDirectories(path.getParent());Files.write(path,bytes);var grid=CollisionGrid.read(path);
        check(grid.hasCoverage()&&grid.map().equals("CoverageFixture")&&grid.cellCount()==1&&grid.blockCount()==1,"SCVXv2 shared native wire fixture");
        check(grid.solid(-1,7,0)&&grid.known(-1,7,0),"negative block occupancy retains one proven solid");
        check(grid.known(-8,0,0)&&grid.known(4,4,4)&&!grid.solid(4,4,4),"known empty space is explicitly certified");
        check(!grid.known(0,0,0)&&!grid.known(-9,0,0)&&!grid.known(16,4,4),"unknown hole and both finite boundaries remain unknown");
        check(grid.knownBlock(-1,0,0)&&grid.knownBlock(1,0,0)&&!grid.knownBlock(0,0,0)&&!grid.knownBlock(2,0,0),"whole fluid cells require known volume, including the single-cell hole");
        var scene=new SifuCollision.Active(grid,new MapPlacement("CoverageFixture",0,0,0));
        check(scene.fluidCell(new net.minecraft.core.BlockPos(1,0,0))&&!scene.fluidCell(net.minecraft.core.BlockPos.ZERO)&&!scene.fluidCell(new net.minecraft.core.BlockPos(2,0,0)),"production fluid queries distinguish known air, an unknown hole and outside coverage");
        check(scene.solidAt(.01,.01,.01)&&!scene.solidAt(1.5,.5,.5)&&scene.solidAt(2.1,.5,.5),"movement/ray boundaries conservatively block unknown cells without filling certified air");
        check(!scene.shapeAt(net.minecraft.core.BlockPos.ZERO).isEmpty()&&scene.solidShapeAt(net.minecraft.core.BlockPos.ZERO).isEmpty(),"unknown blocks movement but cannot invent physical support or displacement");
        int trailer=36+"CoverageFixture".length()+76;
        var legacy=java.util.Arrays.copyOf(bytes,trailer);ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(4,1);Files.write(path,legacy);var old=CollisionGrid.read(path);
        check(!old.hasCoverage()&&!old.known(4,4,4)&&old.known(-1,7,0),"v1 absence is not a coverage certificate");
        rejected(path,java.util.Arrays.copyOf(bytes,bytes.length-1));
        var bad=bytes.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(trailer,-1);rejected(path,bad);
        bad=bytes.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(trailer+4,16);rejected(path,bad);
        bad=bytes.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(trailer+28,65537);rejected(path,bad);
        rejected(path,java.util.Arrays.copyOf(bytes,bytes.length+1));
        Files.write(path,bytes);System.out.println("SCVX coverage PASS "+checks+" shared wire, finite air, unknown hole, negative bounds, legacy and malformed checks");
    }
}
