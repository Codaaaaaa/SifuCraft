package dev.sifucraft.collision;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Sifu's collision as a grid of 1/8-block cells (12.5 cm) in Unreal space, loaded from the grid
 * file sifu-native publishes ("SCVX", see sifu-native/src/collision/export.h). Blocks are 1 m cubes
 * aligned to the Unreal origin; cell (x, y, z) of a block is bit {@code x + 8y} of 64-bit layer
 * {@code z}. Immutable once read, so it can be shared between threads.
 */
public final class CollisionGrid {
    public static final int CELLS_PER_BLOCK = 8;
    private static final int KEY_BITS = 21;
    private static final long KEY_MASK = (1L << KEY_BITS) - 1;
    private static final long KEY_BIAS = 1L << (KEY_BITS - 1);

    private final String map;
    private final float cellCm;
    private final Map<Long, long[]> blocks;
    private final long cellCount;
    private final List<Region> known,unknown;
    private record Region(int x0,int y0,int z0,int x1,int y1,int z1){
        boolean contains(int x,int y,int z){return x>=x0&&x<x1&&y>=y0&&y<y1&&z>=z0&&z<z1;}
        boolean encloses(int x,int y,int z,int endX,int endY,int endZ){return x>=x0&&endX<=x1&&y>=y0&&endY<=y1&&z>=z0&&endZ<=z1;}
        boolean overlaps(int x,int y,int z,int endX,int endY,int endZ){return x<x1&&endX>x0&&y<y1&&endY>y0&&z<z1&&endZ>z0;}
    }

    private CollisionGrid(String map, float cellCm, Map<Long, long[]> blocks, long cellCount,List<Region> known,List<Region> unknown) {
        this.map = map;
        this.cellCm = cellCm;
        this.blocks = blocks;
        this.cellCount = cellCount;
        this.known=known;this.unknown=unknown;
    }

    public static CollisionGrid read(Path path) throws IOException {
        if(Files.size(path)>256L*1024*1024)throw new IOException(path+": grid file too large");
        try{return decode(path,ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN));}
        catch(java.nio.BufferUnderflowException|IllegalArgumentException error){throw new IOException(path+": malformed grid",error);}
    }
    private static CollisionGrid decode(Path path,ByteBuffer in)throws IOException{
        byte[] magic = new byte[4];
        in.get(magic);
        if (!"SCVX".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException(path + ": not a grid file");
        int version = in.getInt();
        if (version != 1&&version!=2) throw new IOException(path + ": grid file version " + version);
        float cellCm = in.getFloat();
        if(cellCm!=12.5f)throw new IOException(path+": unexpected cell size");
        int cellsPerBlock = in.getInt();
        if (cellsPerBlock != CELLS_PER_BLOCK) throw new IOException(path + ": " + cellsPerBlock + " cells per block");
        in.position(in.position() + 12); // pawn feet when the grid was built (debugging only)
        int nameLength=in.getInt();if(nameLength<1||nameLength>1024)throw new IOException(path+": map name length");
        byte[] name = new byte[nameLength];
        in.get(name);
        int count = in.getInt();
        if(count<0||count>2_000_000||count>in.remaining()/76)throw new IOException(path+": block count");
        Map<Long, long[]> blocks = new HashMap<>(count * 2);
        long cells = 0;
        for (int i = 0; i < count; i++) {
            int bx = in.getInt(), by = in.getInt(), bz = in.getInt();
            if(bx<-KEY_BIAS||bx>=KEY_BIAS||by<-KEY_BIAS||by>=KEY_BIAS||bz<-KEY_BIAS||bz>=KEY_BIAS)throw new IOException(path+": block coordinate range");
            long[] layers = new long[CELLS_PER_BLOCK];
            for (int z = 0; z < CELLS_PER_BLOCK; z++) {
                layers[z] = in.getLong();
                cells += Long.bitCount(layers[z]);
            }
            if(blocks.put(key(bx, by, bz), layers)!=null)throw new IOException(path+": duplicate block");
        }
        List<Region> known=version==2?regions(in,4096):null,unknown=version==2?regions(in,65536):List.of();
        if(version==2&&in.hasRemaining())throw new IOException(path+": trailing coverage data");
        return new CollisionGrid(new String(name, StandardCharsets.UTF_8), cellCm, blocks, cells,known,unknown);
    }
    private static List<Region> regions(ByteBuffer in,int limit){
        int n=in.getInt();if(n<0||n>limit||n>in.remaining()/24)throw new IllegalArgumentException("region count");
        var result=new ArrayList<Region>(n);
        for(int i=0;i<n;i++){
            var r=new Region(in.getInt(),in.getInt(),in.getInt(),in.getInt(),in.getInt(),in.getInt());
            if(r.x0>=r.x1||r.y0>=r.y1||r.z0>=r.z1||r.x0<-8388608||r.y0<-8388608||r.z0<-8388608||r.x1>8388608||r.y1>8388608||r.z1>8388608)throw new IllegalArgumentException("region bounds");
            result.add(r);
        }return List.copyOf(result);
    }
    public boolean hasCoverage(){return known!=null;}
    /** Absence of occupancy is not evidence of known air. A proven solid cell is known itself. */
    public boolean known(int x,int y,int z){
        return solid(x,y,z)||known!=null&&known.stream().anyMatch(r->r.contains(x,y,z))&&unknown.stream().noneMatch(r->r.contains(x,y,z));
    }
    public boolean knownBlock(int bx,int by,int bz){
        int x=bx*8,y=by*8,z=bz*8;
        if(known!=null&&known.stream().anyMatch(r->r.encloses(x,y,z,x+8,y+8,z+8))&&unknown.stream().noneMatch(r->r.overlaps(x,y,z,x+8,y+8,z+8)))return true;
        for(int cz=z;cz<z+8;cz++)for(int cy=y;cy<y+8;cy++)for(int cx=x;cx<x+8;cx++)if(!known(cx,cy,cz))return false;
        return true;
    }

    /** Whether Unreal-space cell (x, y, z) is solid (cell = floor(cm / cellCm)). */
    public boolean solid(int x, int y, int z) {
        long[] layers = blocks.get(key(Math.floorDiv(x, CELLS_PER_BLOCK), Math.floorDiv(y, CELLS_PER_BLOCK),
                Math.floorDiv(z, CELLS_PER_BLOCK)));
        if (layers == null) return false;
        int bit = Math.floorMod(x, CELLS_PER_BLOCK) + CELLS_PER_BLOCK * Math.floorMod(y, CELLS_PER_BLOCK);
        return (layers[Math.floorMod(z, CELLS_PER_BLOCK)] >>> bit & 1) != 0;
    }

    /**
     * The top (Unreal cm) of the highest solid cell under a square footprint centred on (x, y) with
     * the given half width, among cells whose top lies between {@code feetZ - maxDropCm} and
     * {@code feetZ + maxRiseCm}; NaN if there is none. Used to stand a player on the grid's floor.
     */
    public double floorUnder(double x, double y, double feetZ, double halfWidthCm, double maxRiseCm, double maxDropCm) {
        int x0 = cell(x - halfWidthCm), x1 = cell(x + halfWidthCm);
        int y0 = cell(y - halfWidthCm), y1 = cell(y + halfWidthCm);
        int zTop = cell(feetZ + maxRiseCm) - 1, zBottom = cell(feetZ - maxDropCm) - 1;
        for (int z = zTop; z >= zBottom; z--) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cx = x0; cx <= x1; cx++) {
                    if (solid(cx, cy, z)) return (z + 1) * (double) cellCm;
                }
            }
        }
        return Double.NaN;
    }

    private int cell(double cm) {
        return (int) Math.floor(cm / cellCm);
    }

    /** The 8 layers of Unreal-space block (bx, by, bz), or null if it holds nothing solid. */
    public long[] block(int bx, int by, int bz) {
        return blocks.get(key(bx, by, bz));
    }

    public String map() {
        return map;
    }

    public float cellCm() {
        return cellCm;
    }

    public int blockCount() {
        return blocks.size();
    }

    public long cellCount() {
        return cellCount;
    }

    static long key(int bx, int by, int bz) {
        return ((bx + KEY_BIAS) & KEY_MASK) | ((by + KEY_BIAS) & KEY_MASK) << KEY_BITS
                | ((bz + KEY_BIAS) & KEY_MASK) << (2 * KEY_BITS);
    }
}
