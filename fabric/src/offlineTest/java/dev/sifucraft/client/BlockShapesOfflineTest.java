package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockSectionIndex;
import dev.sifucraft.collision.MapPlacement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;

public final class BlockShapesOfflineTest {
    private static void check(boolean ok,String why) { if (!ok) throw new AssertionError(why); }
    public static void run() {
        var index=new BlockSectionIndex();
        index.changed(new BlockPos(-1,64,2),Blocks.OAK_SLAB.defaultBlockState());
        index.changed(new BlockPos(0,64,2),Blocks.STONE_BUTTON.defaultBlockState());
        index.changed(new BlockPos(MapSlots.SPACING,64,2),Blocks.STONE.defaultBlockState());
        var placement=new MapPlacement("map",0,64,0);
        check(BlockShapes.matchesWorld(3,"/Game/Maps/map.map",placement),"valid static world survives presentation/F8 changes");
        check(!BlockShapes.matchesWorld(0,"/Game/Maps/map.map",placement),"cleared Native world cannot publish while heartbeat is still fresh");
        check(!BlockShapes.matchesWorld(3,"None.None",placement) && !BlockShapes.matchesWorld(3,"",placement),"loading/exit paths cannot reuse a previous placement");
        check(!BlockShapes.matchesWorld(4,"/Game/Maps/other.other",placement),"new world waits for matching map placement");
        var out=BlockShapes.export(index.snapshots(),EmptyBlockGetter.INSTANCE,placement,1,1,"map",7);
        check(out.sections()==1 && out.payload().length==56,"no button cube and no other-map collision");
        var wire=ByteBuffer.wrap(out.payload()).order(ByteOrder.LITTLE_ENDIAN);
        check(wire.getInt()==-16 && wire.getInt()==0 && wire.getInt()==0 && wire.getInt()==1,"section axis/origin conversion");
        wire.position(32);
        check(wire.getFloat()==1500 && wire.getFloat()==200 && wire.getFloat()==0 &&
              wire.getFloat()==1600 && wire.getFloat()==300 && wire.getFloat()==50,"slab has exact half-height centimetre shape");
        index.changed(new BlockPos(-1,64,2),Blocks.AIR.defaultBlockState());
        out=BlockShapes.export(index.snapshots(),EmptyBlockGetter.INSTANCE,placement,2,1,"map",7);
        check(out.sections()==0 && out.payload().length==0,"full snapshot converges deletions while retaining non-colliding button");
        System.out.println("P8 shape export PASS: map isolation/axis/half slab/button/complete deletion");
    }
}
