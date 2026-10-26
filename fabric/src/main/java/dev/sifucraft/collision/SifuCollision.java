package dev.sifucraft.collision;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Sifu collision currently in force in the Minecraft world: the loaded grid and where its map
 * sits. The client sets it; Minecraft's collision queries read it on the client and on the
 * integrated server (see {@code BlockCollisionsMixin}), so both sides see the same geometry. These
 * are not blocks: they occupy no block positions, so blocks can still be placed among them.
 */
public final class SifuCollision {
    private static final double EPSILON = 1.0E-7;
    private static final double FACE_NUDGE = 1.0E-4; // off a cell face, into the block in front of it

    private static volatile Active active;
    private static final ThreadLocal<Active> query = new ThreadLocal<>();

    private SifuCollision() {}

    public static Active active() {
        return active;
    }

    /** Movement queries can pin one immutable geometry view without replacing the live world. */
    public static Active queryView() { var scoped=query.get();return scoped==null?active:scoped; }
    public static <T> T withQueryView(Active scene,Supplier<T> action) {
        java.util.Objects.requireNonNull(scene);var previous=query.get();query.set(scene);
        try{return action.get();}finally{if(previous==null)query.remove();else query.set(previous);}
    }

    public static void set(Active value) {
        active = value;
    }

    /** One grid in one place, with the Minecraft shape of each of its blocks built on first use. */
    public static final class Active {
        private final CollisionGrid grid;
        private final CollisionGrid doors;
        private final MapPlacement placement;
        private final long sourceSession;
        private final int sourceWorld;
        private final Map<long[], VoxelShape> shapes = new ConcurrentHashMap<>(); // keyed by the grid's own arrays

        public Active(CollisionGrid grid, MapPlacement placement) {
            this(grid, placement, null);
        }
        public Active(CollisionGrid grid, MapPlacement placement, CollisionGrid doors) {
            this(grid,placement,doors,0,0);
        }
        public Active(CollisionGrid grid, MapPlacement placement, CollisionGrid doors, long session, int world) {
            this.grid = grid;
            this.placement = placement;
            this.doors = doors;
            sourceSession=session;sourceWorld=world;
        }
        public boolean matchesScope(long session,int world) { return sourceSession==session&&sourceWorld==world; }
        public boolean sameScope(Active other) { return matchesScope(other.sourceSession,other.sourceWorld)&&placement.equals(other.placement); }
        public CollisionGrid doors() { return doors; }

        public CollisionGrid grid() {
            return grid;
        }

        public MapPlacement placement() {
            return placement;
        }

        /** Native geometry local to one Minecraft block; support queries never create blocks. */
        public VoxelShape shapeAt(BlockPos pos) {
            int x=pos.getX()-placement.originX(), y=pos.getZ()-placement.originZ(), z=pos.getY()-placement.originY();
            long[] fixed=grid.block(x,y,z), moving=doors==null?null:doors.block(x,y,z);
            var a=fixed==null?Shapes.empty():shapes.computeIfAbsent(fixed,SifuCollision::shapeOf);
            var b=moving==null?Shapes.empty():shapes.computeIfAbsent(moving,SifuCollision::shapeOf);
            return a.isEmpty()?b:b.isEmpty()?a:Shapes.or(a,b);
        }

        /** Calls {@code sink} with every Minecraft block position whose Sifu shape may touch {@code box}. */
        public void forEachShape(AABB box, BiConsumer<BlockPos, VoxelShape> sink) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int x0 = Mth.floor(box.minX - EPSILON), x1 = Mth.floor(box.maxX + EPSILON);
            int y0 = Mth.floor(box.minY - EPSILON), y1 = Mth.floor(box.maxY + EPSILON);
            int z0 = Mth.floor(box.minZ - EPSILON), z1 = Mth.floor(box.maxZ + EPSILON);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        // Minecraft (x, y, z) block = Unreal block (x, z, y) shifted by the map's origin.
                        long[] layers = grid.block(x - placement.originX(), z - placement.originZ(), y - placement.originY());
                        if (layers != null) sink.accept(pos.set(x, y, z), shapes.computeIfAbsent(layers, SifuCollision::shapeOf).move(x, y, z));
                        long[] moving = doors == null ? null : doors.block(x - placement.originX(), z - placement.originZ(), y - placement.originY());
                        if (moving != null) sink.accept(pos.set(x, y, z), shapes.computeIfAbsent(moving, SifuCollision::shapeOf).move(x, y, z));
                    }
                }
            }
        }

        /** Whether the Sifu cell holding the Minecraft point (x, y, z) is solid. */
        public boolean solidAt(double x, double y, double z) {
            int perBlock = CollisionGrid.CELLS_PER_BLOCK;
            return solidCell(Mth.floor(x * perBlock), Mth.floor(y * perBlock), Mth.floor(z * perBlock));
        }

        /** Minecraft-space cell (x, y, z) of the 1/8 grid (cell = floor(coordinate * 8)). */
        private boolean solidCell(int x, int y, int z) {
            int perBlock = CollisionGrid.CELLS_PER_BLOCK;
            int gx = x - placement.originX() * perBlock, gy = z - placement.originZ() * perBlock, gz = y - placement.originY() * perBlock;
            return grid.solid(gx, gy, gz) || doors != null && doors.solid(gx, gy, gz);
        }

        /**
         * The first Sifu cell face the segment {@code from -> to} enters, as a hit on the block in
         * front of that face (the block a placed block goes into); null if it enters none, or starts
         * inside Sifu's geometry. Cells are walked one by one along the segment.
         */
        public BlockHitResult clip(Vec3 from, Vec3 to) {
            int perBlock = CollisionGrid.CELLS_PER_BLOCK;
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            int x = Mth.floor(from.x * perBlock), y = Mth.floor(from.y * perBlock), z = Mth.floor(from.z * perBlock);
            if (solidCell(x, y, z)) return null;
            int stepX = (int) Math.signum(dx), stepY = (int) Math.signum(dy), stepZ = (int) Math.signum(dz);
            double deltaX = stepX == 0 ? Double.MAX_VALUE : 1.0 / (perBlock * Math.abs(dx));
            double deltaY = stepY == 0 ? Double.MAX_VALUE : 1.0 / (perBlock * Math.abs(dy));
            double deltaZ = stepZ == 0 ? Double.MAX_VALUE : 1.0 / (perBlock * Math.abs(dz));
            double maxX = stepX == 0 ? Double.MAX_VALUE : ((x + (stepX > 0 ? 1 : 0)) / (double) perBlock - from.x) / dx;
            double maxY = stepY == 0 ? Double.MAX_VALUE : ((y + (stepY > 0 ? 1 : 0)) / (double) perBlock - from.y) / dy;
            double maxZ = stepZ == 0 ? Double.MAX_VALUE : ((z + (stepZ > 0 ? 1 : 0)) / (double) perBlock - from.z) / dz;
            while (true) {
                double t;
                Direction face;
                if (maxX < maxY && maxX < maxZ) {
                    t = maxX;
                    maxX += deltaX;
                    x += stepX;
                    face = stepX > 0 ? Direction.WEST : Direction.EAST;
                } else if (maxY < maxZ) {
                    t = maxY;
                    maxY += deltaY;
                    y += stepY;
                    face = stepY > 0 ? Direction.DOWN : Direction.UP;
                } else {
                    t = maxZ;
                    maxZ += deltaZ;
                    z += stepZ;
                    face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
                }
                if (t > 1.0) return null;
                if (!solidCell(x, y, z)) continue;
                Vec3 hit = new Vec3(from.x + dx * t, from.y + dy * t, from.z + dz * t);
                BlockPos front = BlockPos.containing(hit.x + face.getStepX() * FACE_NUDGE,
                        hit.y + face.getStepY() * FACE_NUDGE, hit.z + face.getStepZ() * FACE_NUDGE);
                return new BlockHitResult(hit, face, front, false);
            }
        }

        /**
         * The horizontal direction (unit, Minecraft axes) from the centre of {@code box} towards the
         * Sifu cells within {@code reach} blocks of its sides, between {@code fromY} and its top: the
         * mean normal of the walls it is against, which irons out the 1/8-block steps of a wall at
         * an angle. Null if there are none.
         */
        public Vec3 wallNormal(AABB box, double fromY, double reach) {
            double cell = grid.cellCm() / MapPlacement.CM_PER_BLOCK;
            double centreX = (box.minX + box.maxX) / 2, centreZ = (box.minZ + box.maxZ) / 2;
            int x0 = Mth.floor((box.minX - reach - placement.originX()) / cell);
            int x1 = Mth.floor((box.maxX + reach - placement.originX()) / cell);
            int y0 = Mth.floor((box.minZ - reach - placement.originZ()) / cell);
            int y1 = Mth.floor((box.maxZ + reach - placement.originZ()) / cell);
            int z0 = Mth.floor((fromY - placement.originY()) / cell);
            int z1 = Mth.floor((box.maxY - EPSILON - placement.originY()) / cell);
            double sumX = 0, sumZ = 0;
            for (int x = x0; x <= x1; x++) {
                double mcX = placement.originX() + (x + 0.5) * cell;
                double outX = Math.max(0, Math.max(box.minX - mcX, mcX - box.maxX));
                for (int y = y0; y <= y1; y++) {
                    double mcZ = placement.originZ() + (y + 0.5) * cell;
                    double outZ = Math.max(0, Math.max(box.minZ - mcZ, mcZ - box.maxZ));
                    if (outX * outX + outZ * outZ > reach * reach) continue;
                    for (int z = z0; z <= z1; z++) {
                        if (!grid.solid(x, y, z) && (doors == null || !doors.solid(x, y, z))) continue;
                        sumX += mcX - centreX;
                        sumZ += mcZ - centreZ;
                    }
                }
            }
            double length = Math.sqrt(sumX * sumX + sumZ * sumZ);
            return length < EPSILON ? null : new Vec3(sumX / length, 0, sumZ / length);
        }
    }

    /** A block's 8x8x8 cells as a Minecraft shape on the same 1/8 grid. */
    static VoxelShape shapeOf(long[] layers) {
        boolean full = true;
        for (long layer : layers) full &= layer == -1L;
        if (full) return Shapes.block();
        BitSetDiscreteVoxelShape cells = new BitSetDiscreteVoxelShape(8, 8, 8);
        for (int lz = 0; lz < layers.length; lz++) {
            for (long bits = layers[lz]; bits != 0; bits &= bits - 1) {
                int bit = Long.numberOfTrailingZeros(bits);
                cells.fill(bit & 7, lz, bit >>> 3); // Unreal (x, y, z) cell -> Minecraft (x, z, y)
            }
        }
        return new CubeVoxelShape(cells);
    }
}
