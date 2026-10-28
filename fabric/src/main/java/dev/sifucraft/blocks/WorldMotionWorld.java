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
    private static volatile java.util.function.Supplier<EnemyFrame> observations;
    private static final ThreadLocal<View> diagnosticView=new ThreadLocal<>();
    public static View current(){
        var diagnostic=diagnosticView.get();if(diagnostic!=null)return diagnostic;
        var view=current;var source=observations;if(view==null||source==null)return view;
        var enemies=source.get();var status=view.requests().status();
        if(enemies==null||enemies.session()!=view.scope().nativeSession()||enemies.worldGeneration()!=view.scope().world())enemies=view.enemies();
        if(status!=null&&status.world()!=view.scope().world())status=null;
        return new View(view.requests(),view.scope(),view.placement(),enemies,view.scene(),status,view.geometry());
    }
    /** The bridge reader supplies fresh immutable observations without calling Minecraft from its thread. */
    public static void observe(java.util.function.Supplier<EnemyFrame> source){observations=source;}
    public static void offer(View view){current=view;PistonMachines.offered(view);}
    /** One server-thread-only seqlock miss, without changing client publication. */
    public static void withUnavailableStatus(Runnable action){
        if(!Boolean.getBoolean("sifucraft.bubbleMatrixSelfTest")&&!Boolean.getBoolean("sifucraft.airEnvironmentSelfTest")&&!Boolean.getBoolean("sifucraft.hotFloorSelfTest"))throw new IllegalStateException("isolated fixture only");
        var view=current();if(view==null)throw new IllegalStateException("missing world");
        var old=diagnosticView.get();diagnosticView.set(new View(view.requests(),view.scope(),view.placement(),view.enemies(),view.scene(),null,view.geometry()));
        try{action.run();}finally{if(old==null)diagnosticView.remove();else diagnosticView.set(old);}
    }
}
