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
    private static final ThreadLocal<View> diagnosticView=new ThreadLocal<>();
    public static View current(){var diagnostic=diagnosticView.get();return diagnostic==null?current:diagnostic;}
    public static void offer(View view){current=view;PistonMachines.offered(view);}
    /** One server-thread-only seqlock miss, without changing client publication. */
    public static void withUnavailableStatus(Runnable action){
        if(!Boolean.getBoolean("sifucraft.bubbleMatrixSelfTest")&&!Boolean.getBoolean("sifucraft.airEnvironmentSelfTest")&&!Boolean.getBoolean("sifucraft.hotFloorSelfTest"))throw new IllegalStateException("isolated fixture only");
        var view=current();if(view==null)throw new IllegalStateException("missing world");
        var old=diagnosticView.get();diagnosticView.set(new View(view.requests(),view.scope(),view.placement(),view.enemies(),view.scene(),null,view.geometry()));
        try{action.run();}finally{if(old==null)diagnosticView.remove();else diagnosticView.set(old);}
    }
}
