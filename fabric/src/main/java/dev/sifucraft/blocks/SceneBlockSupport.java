package dev.sifucraft.blocks;

import dev.sifucraft.collision.SifuCollision;
import java.util.ArrayDeque;
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
        var current=scene(level);return current==null?Shapes.empty():current.shapeAt(pos);
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
        private SifuCollision.Active current, before;
        private ServerLevel sourceLevel;
        private BlockSectionIndex sourceIndex;
        private final ArrayDeque<BlockPos> pending=new ArrayDeque<>();
        public void tick(MinecraftServer server) { update(server.overworld(),WorldBlocks.index(),SifuCollision.active()); }
        public void update(ServerLevel level,BlockSectionIndex index,SifuCollision.Active next) {
            if(level!=sourceLevel||index!=sourceIndex) {
                current=before=null;pending.clear();sourceLevel=level;sourceIndex=index;
            }
            if(next==null||index==null){current=before=null;pending.clear();return;}
            if(next!=current) {
                var old=before==null?current:before;current=next;pending.clear();before=null;
                if(old!=null&&old.sameScope(next)) {
                    before=old;
                    for(var section:index.snapshots())for(short local:section.states().keySet()) {
                        var origin=SectionPos.of(section.section());
                        pending.add(new BlockPos(origin.minBlockX()+SectionPos.sectionRelativeX(local),
                            origin.minBlockY()+SectionPos.sectionRelativeY(local),origin.minBlockZ()+SectionPos.sectionRelativeZ(local)));
                    }
                }
            }
            for(int count=0;count<128&&!pending.isEmpty();count++) {
                var pos=pending.removeFirst();var state=level.getBlockState(pos);
                if(!state.getFluidState().isEmpty()&&geometryChanged(pos)){
                    // Like a solid block replacing a fluid, a newly closed Native
                    // volume displaces that cell's liquid. Do not keep a buried
                    // source which reappears or emits flow through a shut door.
                    if(withScene(current,()->!SceneFluidGeometry.canOccupy(level,pos))){
                        if(state.getBlock() instanceof LiquidBlock)level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
                        else if(state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED))
                            level.setBlockAndUpdate(pos,state.setValue(BlockStateProperties.WATERLOGGED,false));
                    }
                    // Only this directly affected fluid cell needs a new tick.
                    // A removed liquid already notifies its neighbours through
                    // vanilla setBlockAndUpdate; scheduling their neighbours too
                    // would advance drainage one propagation step early.
                    scheduleFluid(level,pos);
                    state=level.getBlockState(pos);
                }
                var checked=state;
                if(checked.isAir()||withScene(current,()->checked.canSurvive(level,pos)))continue;
                if(withScene(before,()->checked.canSurvive(level,pos)))level.destroyBlock(pos,true);
            }
            if(pending.isEmpty())before=null;
        }
        private boolean geometryChanged(BlockPos pos){
            if(before==null)return false;
            if(Shapes.joinIsNotEmpty(before.shapeAt(pos),current.shapeAt(pos),BooleanOp.NOT_SAME))return true;
            for(var face:net.minecraft.core.Direction.values()){var next=pos.relative(face);if(Shapes.joinIsNotEmpty(before.shapeAt(next),current.shapeAt(next),BooleanOp.NOT_SAME))return true;}
            return false;
        }
        private static void scheduleFluid(ServerLevel level,BlockPos pos){var fluid=level.getFluidState(pos);if(!fluid.isEmpty())level.scheduleTick(pos,fluid.getType(),fluid.getType().getTickDelay(level));}
    }
}
