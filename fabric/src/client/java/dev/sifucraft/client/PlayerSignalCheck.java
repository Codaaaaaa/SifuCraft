package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import dev.sifucraft.blocks.PlayerSignalBody;
import dev.sifucraft.blocks.PlayerSignalContacts;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.McState;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Real vanilla queries across control changes; only an explicitly disposable save. */
final class PlayerSignalCheck {
    private static final List<String> PHASES=List.of("MC","Native_same","Native_elsewhere","Native_left","MC_unready","MC_returned","stale","disconnected","map_mismatch","new_world","new_session","Native_dead");
    private final Block[] blocks={Blocks.STONE_PRESSURE_PLATE,Blocks.OAK_PRESSURE_PLATE,Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,Blocks.TRIPWIRE};
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Set<ChunkPos> forced=new HashSet<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private ServerLevel level;
    private ServerPlayer player;
    private Vec3 originalPos,originalVelocity;
    private boolean originalGravity,done;
    private int tick,warmup;
    private long started;
    private McState pinned;
    private volatile double nativePoseError;
    private volatile long nativeSamples;
    private boolean restored;
    private Boolean clientGravity;
    private boolean clientDone;
    private volatile boolean ready,finished;
    private volatile long restoredAt;
    private final boolean needNative=Boolean.getBoolean("sifucraft.signalNativeTest");
    PlayerSignalCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-player-signal-check-"),"disposable save required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("player signals: "+why);}
    McState protectNativePose(McState state,boolean guiReady){
        if(clientDone)return McState.NOT_READY;
        if(pinned==null&&guiReady&&(state.flags()&Protocol.MC_HAS_POSE)!=0)
            pinned=new McState(state.flags(),state.perspective(),state.playerLocation(),state.playerRotation(),SifuFrame.Vec3.ZERO,
                state.cameraLocation(),state.cameraRotation(),state.cameraFov(),state.anchorEpoch(),state.sifuSession(),state.worldGeneration());
        if(pinned==null)return state;
        require(state.sifuSession()==pinned.sifuSession()&&state.worldGeneration()==pinned.worldGeneration()&&state.anchorEpoch()==pinned.anchorEpoch(),"Native scope changed during isolated fixture");
        return pinned.withGui((state.flags()&Protocol.MC_GUI_AVAILABLE)!=0,false,state.guiInputAck());
    }
    void clientTick(Minecraft mc,boolean guiReady,SifuFrame frame){
        if(clientDone)return;
        ready=guiReady&&SifuCraftClient.blockCollisionCurrent();
        if(level!=null&&mc.player!=null){
            if(clientGravity==null)clientGravity=mc.player.isNoGravity();
            mc.player.setNoGravity(true);mc.player.setDeltaMovement(Vec3.ZERO);
        }
        if(needNative&&pinned!=null&&frame!=null&&frame.minecraftOwnsInput()){
            var actual=frame.playerLocation();var feet=pinned.playerLocation();
            double error=Math.sqrt(Math.pow(actual.x()-feet.x(),2)+Math.pow(actual.y()-feet.y(),2)+Math.pow(actual.z()-frame.capsuleHalfHeight()-feet.z(),2));
            nativePoseError=Math.max(nativePoseError,error);nativeSamples++;
            require(error<10,"diagnostic must keep actual Native pawn at its original anchor, error_cm="+error);
        }
        if(!finished)return;
        require(System.currentTimeMillis()-restoredAt<30000,"Native cleanup ACK deadline");
        if(System.currentTimeMillis()-restoredAt<1000||!SifuCraftClient.blockCollisionCurrent())return;
        write(mc.getSingleplayerServer(),"p8-player-signals-native-ack.json",Map.of("result","PASS","fixture_restored",true,"native_revision_applied",true));
        finished=false;clientDone=true;if(mc.player!=null&&clientGravity!=null)mc.player.setNoGravity(clientGravity);mc.stop();
    }
    private void set(BlockPos pos,BlockState state){
        var chunk=new ChunkPos(pos.getX()>>4,pos.getZ()>>4);
        if(!forced.contains(chunk)&&level.setChunkForced(chunk.x(),chunk.z(),true))forced.add(chunk);
        originals.putIfAbsent(pos,level.getBlockState(pos));require(!originals.get(pos).hasBlockEntity(),"fixture has no original BE");
        level.setBlockAndUpdate(pos,state);
    }
    private BlockPos point(int component,boolean target){return new BlockPos(target?28:20,201,16+component*4);}
    private void prepare(MinecraftServer server){
        level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();
        originalPos=player.position();originalVelocity=player.getDeltaMovement();originalGravity=player.isNoGravity();player.setNoGravity(true);
        started=System.currentTimeMillis();
        for(int i=0;i<blocks.length;i++)for(boolean target:new boolean[]{false,true}){
            var pos=point(i,target);
            if(blocks[i]!=Blocks.TRIPWIRE){set(pos.below(),Blocks.STONE.defaultBlockState());set(pos,blocks[i].defaultBlockState());}
            else {
                set(pos.west(3),Blocks.STONE.defaultBlockState());set(pos.east(3),Blocks.STONE.defaultBlockState());
                set(pos.west(2),Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
                set(pos.east(2),Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.WEST));
                for(int dx=-1;dx<=1;dx++)set(pos.offset(dx,0,0),Blocks.TRIPWIRE.defaultBlockState());
            }
        }
    }
    private int power(BlockPos pos){var state=level.getBlockState(pos);return state.hasProperty(BlockStateProperties.POWER)?state.getValue(BlockStateProperties.POWER):state.getValue(BlockStateProperties.POWERED)?15:0;}
    private static void write(MinecraftServer server,String file,Object report){
        try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve(file),out->new GsonBuilder().setPrettyPrinting().create().toJson(report,out));}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    void tick(MinecraftServer server){
        if(done||server.getPlayerList().getPlayers().isEmpty())return;
        if(needNative&&level==null&&!ready)return;
        try{
            if(level==null){prepare(server);return;}
            require(System.currentTimeMillis()-started<180000,"fixture deadline");
            if(originals.keySet().stream().anyMatch(p->!level.isPositionEntityTicking(p)))return;
            if(warmup++<40)return;
            int component=tick/(PHASES.size()*24),phase=(tick/24)%PHASES.size();
            var old=point(component,false);var target=point(component,true);
            boolean mc=phase==0||phase==4||phase==5;
            Vec3 mcPos=Vec3.atBottomCenterOf(phase>=5?target:old).add(0,.02,0);
            Vec3 nativePos=Vec3.atBottomCenterOf(phase==2||phase==5||phase==10?target:old).add(0,.02,0);
            if(phase==3)nativePos=nativePos.add(0,0,2);
            if(tick%24==0)player.teleportTo(level,mcPos.x,mcPos.y,mcPos.z,Set.of(),player.getYRot(),player.getXRot(),true);
            else player.setPos(mcPos);
            player.setDeltaMovement(Vec3.ZERO);
            long age=phase==6?5000:0;
            var location=new SifuFrame.Vec3((float)(nativePos.x*100),(float)(nativePos.z*100),(float)(nativePos.y*100+90));
            var frame=new SifuFrame(tick+1,(System.currentTimeMillis()-age)*1000,phase>=9?2:1,Protocol.SNAPSHOT_HAS_PAWN,
                location,SifuFrame.Vec3.ZERO,SifuFrame.Vec3.ZERO,location,SifuFrame.Vec3.ZERO,75,phase==11?0:120,120,
                phase==8?"/SifuCraft/Test/elsewhere.elsewhere":"/SifuCraft/Test/signals.signals",0,0,0,mc?Protocol.OWNER_MINECRAFT:Protocol.OWNER_SIFU,90,1);
            if(phase==7)PlayerSignalContacts.offer(null,0,null,null,null,false);
            else PlayerSignalContacts.offer(server,phase>=10?2:1,frame,new MapPlacement("signals",0,0,0),player.getUUID(),phase!=4);
            float hp=player.getHealth();PlayerSignalContacts.tick(server);
            require(player.position().equals(mcPos)&&player.getDeltaMovement().equals(Vec3.ZERO)&&player.getHealth()==hp,"contact does not teleport/accelerate/damage backing player");
            player.applyEffectsFromBlocks(mcPos,mcPos);
            boolean activeBody=!mc&&phase!=6&&phase!=7&&phase!=8&&phase!=11;
            var bodies=level.getEntitiesOfClass(PlayerSignalBody.class,new AABB(0,195,0,64,210,64));
            require(bodies.size()==(activeBody?1:0),"one Native body or zero after scope/control cleanup, phase="+PHASES.get(phase));
            require(player.isIgnoringBlockTriggers()==(phase!=0&&phase!=5),"only current MC player may trigger at its real position");
            if(activeBody){var body=bodies.getFirst();require(body.position().distanceTo(nativePos)<1e-5&&body.noPhysics&&!body.isPickable(),"signal body matches snapshot, invisible to attacks");}
            if(tick%24==23){
                int on=component>=2&&component<=3?1:15;
                int oldExpected=phase==0||phase==1||phase==9?on:0;
                int newExpected=phase==2||phase==5||phase==10?on:0;
                require(power(old)==oldExpected&&power(target)==newExpected,"vanilla output "+component+"/"+PHASES.get(phase)+" old="+power(old)+" new="+power(target));
                if(component==4)for(boolean to:new boolean[]{false,true}){
                    var pos=point(component,to);int expected=to?newExpected:oldExpected;
                    require(power(pos.west(2))==expected&&power(pos.east(2))==expected,"both hooks reflect current player presence");
                }
                rows.add(Map.of("block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blocks[component]).toString(),"phase",PHASES.get(phase),"old",power(old),"new",power(target),"bodies",bodies.size()));
            }
            if(++tick<blocks.length*PHASES.size()*24)return;
            done=true;restore(server);
            require(!needNative||nativeSamples>0,"actual Native pose sampled while pinning diagnostic output");
            write(server,"p8-player-signals.json",Map.of("result","PASS","phases",rows,"ticks",tick,"actual_native_input",false,"native_samples",nativeSamples,"native_pose_max_error_cm",nativePoseError));
            LoggerFactory.getLogger("sifucraft").info("[player-signal-test] PASS {} ticks, {} block/control cases; vanilla strengths, scope release, one body, backing pose/HP unchanged",tick,rows.size());
            if(needNative){restoredAt=System.currentTimeMillis();finished=true;}else Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
        }catch(Exception|AssertionError e){
            write(server,"p8-player-signals-failure.json",Map.of("tick",tick,"error",e.toString(),"completed",rows));done=true;restore(server);
            LoggerFactory.getLogger("sifucraft").error("[player-signal-test] FAIL tick="+tick,e);Minecraft.getInstance().execute(()->{throw new AssertionError(e);});
        }
    }
    void restore(MinecraftServer server){
        PlayerSignalContacts.stop();
        if(level==null||restored)return;restored=true;
        for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();
        for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();
        if(player!=null){player.teleportTo(level,originalPos.x,originalPos.y,originalPos.z,Set.of(),player.getYRot(),player.getXRot(),true);
            player.setDeltaMovement(originalVelocity);player.setNoGravity(originalGravity);}
    }
}
