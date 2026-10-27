package dev.sifucraft.blocks;

import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.collision.MapPlacement;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** One signal presence across F8: the real MC player, or a Native snapshot body, never both. */
public final class PlayerSignalContacts {
    private record View(MinecraftServer host,long session,SifuFrame frame,MapPlacement placement,UUID player,boolean ready){}
    private static volatile View requested;
    // The fields below belong to the integrated server thread, including block trigger queries.
    private static ServerPlayer backing;
    private static PlayerSignalBody body;
    private static long session;
    private static int world;
    private static String map;
    private static boolean suppress;
    private static long playerStamp;
    private static float rootRadius;
    private PlayerSignalContacts(){}
    public static void offer(MinecraftServer host,long session,SifuFrame frame,MapPlacement placement,UUID player,boolean ready) {
        requested=host!=null&&session!=0&&frame!=null&&frame.hasPawn()&&frame.worldGeneration()!=0&&placement!=null&&player!=null
            ?new View(host,session,frame,placement,player,ready):null;
    }
    public static boolean ignores(Entity entity){return suppress&&entity==backing&&entity.level().dimension()==Level.OVERWORLD;}
    public static PlayerSignalBody nativeBody(){return suppress&&body!=null&&!body.isRemoved()?body:null;}
    static EnemyFrame.Entry nativeEntry(WorldMotionWorld.View view){
        var source=requested;var entity=nativeBody();
        if(source==null||entity==null||rootRadius<=0||view==null||view.nativeStatus()==null||view.nativeStatus().playerStamp()!=playerStamp||
           source.session!=view.scope().nativeSession()||world!=view.scope().world()||!source.placement.equals(view.placement()))return null;
        var p=entity.position();float half=entity.getBbHeight()*50;
        return new EnemyFrame.Entry(0,0,new SifuFrame.Vec3((float)view.placement().ueX(p.x),(float)view.placement().ueY(p.z),(float)view.placement().ueZ(p.y)+half),
            source.frame.playerRotation().y(),rootRadius,half,source.frame.health(),source.frame.maxHealth(),Protocol.ENEMY_ALIVE,0,SifuFrame.Vec3.ZERO);
    }
    public static void tick(MinecraftServer server) {
        var view=requested;
        if(!WorldBlocks.ENABLED){clear();return;}
        if(view==null||view.host!=server){suspend();return;}
        var frame=view.frame;
        long age=System.currentTimeMillis()-frame.unixMicros()/1000;
        var player=server.getPlayerList().getPlayer(view.player);
        if(age< -1000||age>1000||player==null||!player.isAlive()||player.isSpectator()||player.level()!=server.overworld()) {suspend();return;}
        String path=frame.worldPath();int dot=path.lastIndexOf('.');String frameMap=dot<0?path:path.substring(dot+1);
        if(!view.placement.map().equals(frameMap)){suspend();return;}
        if(backing!=player||session!=view.session||world!=frame.worldGeneration()||!view.placement.map().equals(map))clear();
        backing=player;session=view.session;world=frame.worldGeneration();map=view.placement.map();
        suppress=!frame.minecraftOwnsInput()||!view.ready;
        if(frame.minecraftOwnsInput()){removeBody();return;}
        var motion=WorldMotionWorld.current();
        if(motion!=null&&motion.scope().nativeSession()==view.session&&motion.scope().world()==world&&motion.placement().equals(view.placement())&&motion.nativeStatus()!=null){
            var status=motion.nativeStatus();long now=WorldWriter.clockMillis();
            if(status.atMs()<=now&&now-status.atMs()<=200){
                if(playerStamp!=status.playerStamp()){playerStamp=status.playerStamp();rootRadius=0;}
                if(status.player()!=null)rootRadius=status.player().radius();
            }
        }
        float height=frame.capsuleHalfHeight()*2/100f;
        var pos=dev.sifucraft.combat.FluidMotion.playerPose(motion,frame,PistonMachines.playerPose(motion,frame,frame.playerLocation()));
        if(!Float.isFinite(frame.health())||frame.health()<=0||!Float.isFinite(height)||height<=0||height>40||!Float.isFinite(pos.x())||!Float.isFinite(pos.y())||!Float.isFinite(pos.z())){removeBody();return;}
        boolean create=body==null||body.isRemoved();
        if(create)body=new PlayerSignalBody(SignalEntities.PLAYER_BODY,server.overworld());
        body.place(view.placement.mcX(pos.x()),view.placement.mcY(pos.z()-frame.capsuleHalfHeight()),
            view.placement.mcZ(pos.y()),rootRadius>0?rootRadius*2/100:player.getBbWidth(),height);
        if(create&&!server.overworld().addFreshEntity(body)){removeBody();return;}
        body.updateSignals();
    }
    private static void removeBody(){if(body!=null){body.discard();PistonMachines.playerGone();dev.sifucraft.combat.FluidMotion.playerGone();}body=null;}
    // A stalled/disconnected Native source must release its body, not reactivate the old MC pose.
    private static void suspend(){removeBody();suppress=backing!=null;}
    private static void clear(){removeBody();backing=null;session=0;world=0;map=null;suppress=false;playerStamp=0;rootRadius=0;}
    public static void stop(){requested=null;clear();}
}
