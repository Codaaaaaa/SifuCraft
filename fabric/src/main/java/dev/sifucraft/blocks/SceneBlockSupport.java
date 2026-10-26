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
                if(state.isAir()||withScene(current,()->state.canSurvive(level,pos)))continue;
                if(withScene(before,()->state.canSurvive(level,pos)))level.destroyBlock(pos,true);
            }
            if(pending.isEmpty())before=null;
        }
    }
}
