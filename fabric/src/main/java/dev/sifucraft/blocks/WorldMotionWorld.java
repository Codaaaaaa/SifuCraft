package dev.sifucraft.blocks;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.WorldMotionMailbox;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;

/** Immutable client publication for world mechanics, independent of the input owner/anchor epoch. */
public final class WorldMotionWorld {
    private WorldMotionWorld(){}
    public record Geometry(long index,long motion,long revision,boolean current){}
    public record View(WorldMotionRequests requests,WorldMotionRequests.Scope scope,MapPlacement placement,
                       EnemyFrame enemies,SifuCollision.Active scene,WorldMotionMailbox.NativeStatus nativeStatus,
                       Geometry geometry) {
        public boolean shapesCurrent(){return geometry.current;}
        public PistonMovePlans.View pistonView(){
            return new PistonMovePlans.View(new PistonMovePlans.Scope(scope.mc(),scope.nativeSession(),scope.world(),scope.save(),placement),enemies,scene);
        }
    }
    private static volatile View current;
    public static View current(){return current;}
    public static void offer(View view){current=view;PistonMachines.offered(view);}
}
