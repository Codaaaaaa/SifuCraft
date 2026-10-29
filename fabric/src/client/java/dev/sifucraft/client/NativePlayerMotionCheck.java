package dev.sifucraft.client;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.SceneBlockSupport;
import dev.sifucraft.blocks.ShapeChanges;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.storage.AtomicFiles;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Disposable real player: 100ms no-move handback, then one bounded 10cm sweep. */
final class NativePlayerMotionCheck {
    private final UUID source=UUID.randomUUID();
    private final long action=ShapeProtocol.mapHash("sifucraft:player-mechanical-probe");
    private volatile SifuFrame frame;private volatile boolean finished;
    private ServerLevel level;private BlockPos marker;private BlockState original;
    private WorldMotionRequests.Scope scope;private long stamp,lease,started,stableAt,stateHash,sectionRevision,stepRevision;
    private int stage,reserves,sweeps;private boolean moving;
    private float health,radius,half;private Reply binding,last;private SifuFrame.Vec3 initial,finalPosition;
    NativePlayerMotionCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-player-motion-check-"),"disposable save required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("native player motion: "+why);}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished)mc.stop();}
    private static double distance(SifuFrame.Vec3 a,SifuFrame.Vec3 b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    private boolean geometry(WorldMotionWorld.View v){var index=WorldBlocks.index();return index!=null&&v.shapesCurrent()&&v.geometry().index()==index.revision()&&v.geometry().motion()==ShapeChanges.revision()&&v.nativeStatus().shapeRevision()==v.geometry().revision();}
    private boolean fresh(WorldMotionWorld.View v){long now=WorldWriter.clockMillis();return v!=null&&v.nativeStatus()!=null&&v.nativeStatus().atMs()<=now&&now-v.nativeStatus().atMs()<=200&&v.nativeStatus().flags()==0&&v.placement().map().equals("Hideout_1_Main")&&v.scope().equals(v.requests().scope());}
    private boolean eligible(WorldMotionWorld.View v){return fresh(v)&&(v.nativeStatus().capabilities()&PLAYER_MACHINE_CAPABILITY)!=0&&v.nativeStatus().player()!=null;}
    void tick(MinecraftServer server){
        if(finished)return;
        if(!dev.sifucraft.bridge.LiveTestFocus.allowed())return;
        try{
            var v=WorldMotionWorld.current();var snapshot=frame;
            if(!fresh(v)||snapshot==null||snapshot.minecraftOwnsInput())return;
            if(started==0){started=System.currentTimeMillis();level=server.overworld();}
            require(System.currentTimeMillis()-started<30000,"bounded deadline stage="+stage+" capabilities="+v.nativeStatus().capabilities());
            if(scope!=null)require(scope.equals(v.scope())&&stamp==v.nativeStatus().playerStamp()&&snapshot.health()==health,"same player/scope/HP throughout");
            if(stage==0){
                if(!eligible(v)||!geometry(v))return;
                var p=v.nativeStatus().player();stamp=v.nativeStatus().playerStamp();scope=v.scope();health=p.health();radius=p.radius();half=p.halfHeight();initial=p.position();
                var feet=new BlockPos((int)Math.floor(v.placement().mcX(initial.x())),(int)Math.floor(v.placement().mcY(initial.z()-half)),(int)Math.floor(v.placement().mcZ(initial.y())));
                for(int dx:new int[]{5,-5,6,-6}){var candidate=feet.offset(dx,3,0);if(level.getBlockState(candidate).isAir()&&SceneBlockSupport.nativeShape(level,candidate).isEmpty()){marker=candidate;break;}}
                require(marker!=null,"empty isolated marker location");original=level.getBlockState(marker);level.setBlockAndUpdate(marker,Blocks.STONE.defaultBlockState());
                stateHash=ShapeProtocol.mapHash(BlockStateKey.encode(Blocks.STONE.defaultBlockState()));sectionRevision=WorldBlocks.index().snapshot(SectionPos.of(marker).asLong()).revision();stage=1;return;
            }
            if(stage==1){if(eligible(v)&&geometry(v))reserve(v,false);return;}
            if(stage==3){
                require(distance(snapshot.playerLocation(),initial)<.2,"no-move lease did not alter the real player");
                if(System.currentTimeMillis()-stableAt>=1000&&eligible(v)&&geometry(v))reserve(v,true);return;
            }
            if(stage==5){if(geometry(v)&&v.nativeStatus().shapeRevision()>stepRevision){stage=6;release(v,true);}return;}
            if(stage==7){
                require(distance(snapshot.playerLocation(),finalPosition)<.2,"real player remains at observed endpoint after handback");
                if(System.currentTimeMillis()-stableAt<3000)return;
                require(sweeps==1&&reserves==2&&v.requests().pending()==0,"one actual sweep and two explicit handbacks");
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-native-player-motion.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","player_stamp",Long.toUnsignedString(stamp),"root_radius_cm",radius,"root_half_cm",half,"native_hp",health,"actual_delta_cm",finalPosition.x()-initial.x(),"restored_mask",1,"reserves",reserves,"sweeps",sweeps,"stable_ms",3000),out));
                restore(server);finished=true;LoggerFactory.getLogger("sifucraft").info("[native-player-motion-test] PASS actual no-move/10cm root sweep, player movement restored, HP={}, stable3s",health);
            }
        }catch(Exception|AssertionError e){fail(e);}
    }
    private Command command(WorldMotionWorld.View v,int op,long id,SifuFrame.Vec3 position,SifuFrame.Vec3 delta){
        return new Command(op,MACHINE,id,op==RESERVE?0:lease,WorldWriter.clockMillis(),source.getMostSignificantBits(),source.getLeastSignificantBits(),action,level.getGameTime(),
            scope.save(),scope.map(),v.nativeStatus().shapeRevision(),scope.world(),0,0,op==RESERVE?(moving?500:100):0,position,delta,health,radius,half,PLAYER,
            marker.getX(),marker.getY(),marker.getZ(),0,0,0,stateHash,sectionRevision,op==RESERVE?stamp:binding.actorSerial(),op==RESERVE?0:binding.componentToken());
    }
    private void watch(CompletableFuture<Reply> future,Consumer<Reply> receive){future.whenComplete((r,error)->level.getServer().execute(()->{if(finished||!dev.sifucraft.bridge.LiveTestFocus.allowed())return;try{if(error!=null)throw new AssertionError(error);receive.accept(r);}catch(Exception|AssertionError e){fail(e);}}));}
    private WorldMotionWorld.View current(){var v=WorldMotionWorld.current();require(fresh(v)&&scope.equals(v.scope()),"fresh same-scope callback");return v;}
    private void reserve(WorldMotionWorld.View v,boolean sweep){
        moving=sweep;stage=sweep?4:2;var position=v.nativeStatus().player().position();binding=null;reserves++;
        var frozen=new AtomicReference<Command>();
        var reserved=v.requests().request(scope,id->{lease=id;var c=command(v,RESERVE,id,position,SifuFrame.Vec3.ZERO);frozen.set(c);return c;});
        if(!sweep){
            // M0 has no world mutation between the receipts. Queue its stop on
            // the IPC thread instead of spending another 50ms server tick out
            // of the unchanged 100ms hard deadline. Only immutable wire data
            // crosses this callback; Level and fixture state stay server-owned.
            var prepared=new AtomicReference<Reply>();
            var released=reserved.thenCompose(r->{
                var c=frozen.get();
                require(r.status()==1&&r.actorSerial()==(int)c.actorStamp()&&r.componentToken()!=0&&r.health()==c.health()&&r.rootRadius()==c.radius()&&r.rootHalfHeight()==c.halfHeight(),"actual player Prepared and capsule binding: "+r);
                var next=WorldMotionWorld.current();require(next!=null&&v.scope().equals(next.scope()),"same scope for immediate no-move handback");
                prepared.set(r);
                return next.requests().request(v.scope(),id->new Command(RELEASE,c.source(),id,c.id(),WorldWriter.clockMillis(),c.sourceHi(),c.sourceLo(),c.action(),c.tick(),
                    c.save(),c.map(),r.shapeRevision(),c.world(),c.target(),c.spawn(),0,r.position(),SifuFrame.Vec3.ZERO,c.health(),c.radius(),c.halfHeight(),c.flags(),
                    c.blockX(),c.blockY(),c.blockZ(),c.progress(),c.nextProgress(),0,c.stateHash(),c.sectionRevision(),r.actorSerial(),r.componentToken()));
            });
            watch(released,r->{binding=prepared.get();finishRelease(r,false);});
            return;
        }
        watch(reserved,r->{
            require(r.status()==1&&r.actorSerial()==(int)stamp&&r.componentToken()!=0&&r.health()==health&&r.rootRadius()==radius&&r.rootHalfHeight()==half,"actual player Prepared and capsule binding: "+r);binding=last=r;
            var next=current();if(!moving){release(next,false);return;}
            stepRevision=next.nativeStatus().shapeRevision();
            watch(next.requests().request(scope,id->command(next,STEP,id,r.position(),new SifuFrame.Vec3(10,0,0))),actual->{
                require(actual.status()==2&&actual.actorSerial()==binding.actorSerial()&&actual.componentToken()==binding.componentToken()&&actual.health()==health&&Math.abs(actual.delta().x()-10)<.1&&Math.abs(actual.delta().y())<.1&&Math.abs(actual.delta().z())<.1,"one actual horizontal sweep: "+actual);
                sweeps++;last=actual;level.setBlockAndUpdate(marker,original);stage=5;
            });
        });
    }
    private void release(WorldMotionWorld.View v,boolean swept){
        watch(v.requests().request(scope,id->command(v,RELEASE,id,last.position(),SifuFrame.Vec3.ZERO)),r->finishRelease(r,swept));
    }
    private void finishRelease(Reply r,boolean swept){
            require(r.status()==3&&r.restored()==1&&r.actorSerial()==binding.actorSerial()&&r.componentToken()==binding.componentToken()&&r.health()==health,"explicit original player movement restoration: "+r);
            last=r;binding=null;stableAt=System.currentTimeMillis();
            if(swept){finalPosition=r.position();stage=7;}else{require(distance(initial,r.position())<.1,"100ms no-move pose unchanged");stage=3;LoggerFactory.getLogger("sifucraft").info("[native-player-motion-test] M0 PASS no movement, actual player movement tick restored HP={}",health);}
    }
    private void fail(Throwable error){
        if(finished)return;LoggerFactory.getLogger("sifucraft").error("[native-player-motion-test] FAIL",error);
        var v=WorldMotionWorld.current();if(binding!=null&&fresh(v)&&scope.equals(v.scope()))v.requests().request(scope,id->command(v,CANCEL,id,last.position(),SifuFrame.Vec3.ZERO));
        restore(level==null?null:level.getServer());finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(error);});
    }
    void restore(MinecraftServer ignored){if(level!=null&&marker!=null&&original!=null)level.setBlockAndUpdate(marker,original);}
}
