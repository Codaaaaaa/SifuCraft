package dev.sifucraft.blocks;

import dev.sifucraft.collision.SifuCollision;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Native collision contributes support, without becoming a saved block or a signal conductor. */
public final class SceneBlockSupport {
    private static final ThreadLocal<SifuCollision.Active> QUERY_SCENE = new ThreadLocal<>();
    private SceneBlockSupport() {}
    static SifuCollision.Active scopedScene(){return QUERY_SCENE.get();}

    public static <T> T withScene(SifuCollision.Active scene, Supplier<T> query) {
        var previous=QUERY_SCENE.get();QUERY_SCENE.set(scene);
        try{return query.get();}finally{if(previous==null)QUERY_SCENE.remove();else QUERY_SCENE.set(previous);}
    }
    static SifuCollision.Active scene(BlockGetter level) {
        if(!(level instanceof Level world) || world.dimension()!=Level.OVERWORLD)return null;
        var scoped=QUERY_SCENE.get();return scoped==null?SifuCollision.active():scoped;
    }
    public static VoxelShape nativeShape(BlockGetter level,BlockPos pos) {
        if(!WorldBlocks.ENABLED)return Shapes.empty();
        var current=scene(level);return current==null?Shapes.empty():current.solidShapeAt(pos);
    }
    public static VoxelShape supportShape(BlockGetter level,BlockPos pos,VoxelShape vanilla) {
        var nativeShape=nativeShape(level,pos);
        return nativeShape.isEmpty()?vanilla:vanilla.isEmpty()?nativeShape:Shapes.or(vanilla,nativeShape);
    }
    public static boolean intersects(Level level,BlockPos pos,BlockState state) {
        if(scene(level)==null)return false;
        var shape=Shapes.or(state.getShape(level,pos),state.getCollisionShape(level,pos));
        return intersects(level,shape.move(pos.getX(),pos.getY(),pos.getZ()));
    }
    public static boolean intersects(Level level,VoxelShape worldShape) {
        var current=scene(level);if(current==null||worldShape.isEmpty())return false;
        boolean[] overlaps={false};
        current.forEachShape(worldShape.bounds(),(at,nativeShape)->{
            if(!overlaps[0]&&Shapes.joinIsNotEmpty(worldShape,nativeShape,BooleanOp.AND))overlaps[0]=true;
        });
        return overlaps[0];
    }

    /** Server-owned, bounded revalidation after an actual same-map geometry change. */
    public static final class Updates {
        private SifuCollision.Active current;
        private ServerLevel sourceLevel;
        private BlockSectionIndex sourceIndex;
        private long loadedRevision;
        private final LinkedHashMap<BlockPos,SifuCollision.Active> pending=new LinkedHashMap<>();
        public void tick(MinecraftServer server) { update(server.overworld(),WorldBlocks.index(),SifuCollision.active()); }
        public void update(ServerLevel level,BlockSectionIndex index,SifuCollision.Active next) {
            if(level!=sourceLevel||index!=sourceIndex) {
                current=null;pending.clear();loadedRevision=0;sourceLevel=level;sourceIndex=index;
            }
            if(next==null||index==null){current=null;pending.clear();loadedRevision=0;return;}
            if(next!=current) {
                var old=current;current=next;
                if(old!=null&&!old.sameScope(next)){old=null;pending.clear();}
                enqueue(index.snapshots(),old,next);
            }
            long loaded=index.loadedRevision();
            if(loaded!=loadedRevision){enqueue(index.loadedAfter(loadedRevision),null,next);loadedRevision=loaded;}
            if(!SceneFluidGeometry.available(level))return;
            int budget=Math.min(128,pending.size());
            for(int count=0;count<budget&&!pending.isEmpty();count++) {
                var item=pending.pollFirstEntry();var pos=item.getKey();var before=item.getValue();var state=level.getBlockState(pos);
                // Unknown is a waiting state, not a newly closed wall. Keep both
                // the saved liquid and the earliest support baseline until queried.
                boolean known=current.knownBlock(pos);
                for(var face:net.minecraft.core.Direction.values())known&=current.knownBlock(pos.relative(face));
                if(!known){pending.put(pos,before);SceneCoverage.request(new net.minecraft.world.phys.AABB(pos).inflate(1));continue;}
                if(!state.getFluidState().isEmpty()){
                    boolean changed=before==null||geometryChanged(before,pos);
                    // Like a solid block replacing a fluid, a newly closed Native
                    // volume displaces that cell's liquid. Do not keep a buried
                    // source which reappears or emits flow through a shut door.
                    boolean displaced=withScene(current,()->!SceneFluidGeometry.canOccupy(level,pos));
                    if(displaced){
                        if(state.getBlock() instanceof LiquidBlock)level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
                        else if(state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED))
                            level.setBlockAndUpdate(pos,state.setValue(BlockStateProperties.WATERLOGGED,false));
                    }
                    // Only this directly affected fluid cell needs a new tick.
                    // A removed liquid already notifies its neighbours through
                    // vanilla setBlockAndUpdate; scheduling their neighbours too
                    // would advance drainage one propagation step early.
                    if(changed||displaced)scheduleFluid(level,pos);
                    state=level.getBlockState(pos);
                }
                var checked=state;
                if(checked.isAir()||withScene(current,()->checked.canSurvive(level,pos)))continue;
                if(before!=null&&withScene(before,()->checked.canSurvive(level,pos)))level.destroyBlock(pos,true);
            }
        }
        private void enqueue(java.util.List<BlockSectionIndex.Snapshot> snapshots,SifuCollision.Active old,SifuCollision.Active next){
            for(var section:snapshots)for(short local:section.states().keySet()){
                var origin=SectionPos.of(section.section());
                var pos=new BlockPos(origin.minBlockX()+SectionPos.sectionRelativeX(local),origin.minBlockY()+SectionPos.sectionRelativeY(local),origin.minBlockZ()+SectionPos.sectionRelativeZ(local));
                // Keep the earliest unprocessed baseline and queue position.
                // Rapid door revisions must not restart a large scan forever.
                if(next.placement().containsX(pos.getX())&&(!pending.containsKey(pos)||old==null))pending.put(pos,old);
            }
        }
        private boolean geometryChanged(SifuCollision.Active before,BlockPos pos){
            if(Shapes.joinIsNotEmpty(before.shapeAt(pos),current.shapeAt(pos),BooleanOp.NOT_SAME))return true;
            for(var face:net.minecraft.core.Direction.values()){var next=pos.relative(face);if(Shapes.joinIsNotEmpty(before.shapeAt(next),current.shapeAt(next),BooleanOp.NOT_SAME))return true;}
            return false;
        }
        private static void scheduleFluid(ServerLevel level,BlockPos pos){var fluid=level.getFluidState(pos);if(!fluid.isEmpty())level.scheduleTick(pos,fluid.getType(),fluid.getType().getTickDelay(level));}
    }
}
