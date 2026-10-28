package dev.sifucraft.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;
import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static com.mojang.brigadier.arguments.DoubleArgumentType.*;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;

/** Commands never move a proxy or spawn an MC stand-in. Native returns the actual outcome. */
final class NpcDebugCommands {
    private final Minecraft mc;private final McBridge bridge;
    private final AtomicBoolean pending=new AtomicBoolean();private final AtomicLong ids=new AtomicLong();
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"sifucraft-npc-debug");t.setDaemon(true);return t;});
    private NpcDebugCommands(Minecraft mc,McBridge bridge){this.mc=mc;this.bridge=bridge;}
    static void install(Minecraft mc,McBridge bridge){
        if(!Boolean.getBoolean("sifucraft.npcDebug"))return;
        var tool=new NpcDebugCommands(mc,bridge);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->tool.io.shutdownNow());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->{
            var npc=literal("npc").requires(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource::attended).then(literal("list").executes(ctx->tool.list()))
                .then(literal("clear").executes(ctx->tool.send(NpcDebugProtocol.CLEAR,0,0,Vec3.ZERO)));
            var move=argument("id",integer(1));
            move.then(argument("x",doubleArg()).then(argument("y",doubleArg()).then(argument("z",doubleArg())
                .executes(ctx->tool.send(NpcDebugProtocol.MOVE,0,getInteger(ctx,"id"),new Vec3(getDouble(ctx,"x"),getDouble(ctx,"y"),getDouble(ctx,"z")))))));
            npc.then(literal("move").then(move));
            var spawn=literal("spawn");
            for(int kind=1;kind<=2;kind++){
                final int selected=kind;
                spawn.then(literal(kind==1?"grunt60":"grunt100").then(argument("x",doubleArg()).then(argument("y",doubleArg()).then(argument("z",doubleArg())
                    .executes(ctx->tool.send(NpcDebugProtocol.SPAWN,selected,0,new Vec3(getDouble(ctx,"x"),getDouble(ctx,"y"),getDouble(ctx,"z"))))))));
            }
            dispatcher.register(literal("sifucraft").then(npc.then(spawn)));
        });
    }
    private void message(String value){mc.execute(()->{if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("[NPC] "+value));});}
    private int list(){
        var v=WorldMotionWorld.current();if(v==null||mc.player==null){message("尚未连接实际场景");return 0;}
        message("当前地图 "+v.placement().map()+"；坐标为MC脚底坐标，move使用绝对坐标");
        for(var e:v.enemies().entries())if(e.alive())message(String.format(java.util.Locale.ROOT,"id=%d spawn=%d HP=%.2f/%.2f pos=(%.3f, %.3f, %.3f)",e.id(),e.spawn(),e.health(),e.maxHealth(),v.placement().mcX(e.location().x()),v.placement().mcY(e.location().z()-e.halfHeight()),v.placement().mcZ(e.location().y())));
        return 1;
    }
    private int send(int op,int kind,int target,Vec3 at){
        var v=WorldMotionWorld.current();var frame=bridge.latest();
        if(v==null||mc.player==null||mc.getSingleplayerServer()==null||!bridge.peerConnected()||frame==null||!v.shapesCurrent()||v.nativeStatus()==null||v.nativeStatus().flags()!=0){message("需要已连接、几何就绪且未暂停的本地场景");return 0;}
        var e=v.enemies().entries().stream().filter(entry->entry.id()==target&&entry.alive()).findFirst().orElse(null);
        if(op==NpcDebugProtocol.MOVE&&e==null){message("目标已失效，请重新执行list");return 0;}
        if(op!=NpcDebugProtocol.CLEAR){
            if(!Double.isFinite(at.lengthSqr())||at.distanceTo(mc.player.position())>30){message("落点须在玩家30米内");return 0;}
            var box=new AABB(at.x-.35,at.y+.01,at.z-.35,at.x+.35,at.y+1.8,at.z+.35);
            if(!dev.sifucraft.blocks.SceneFluidGeometry.knownVolume(mc.level,box)){message("正在请求落点覆盖，请稍后重试");return 0;}
            if(!mc.level.noCollision(null,box)){message("落点存在方块/场景障碍");return 0;}
        }
        var manifest=bridge.collisionManifest();
        if(manifest.path().isEmpty()){message("尚无实际场景目录");return 0;}
        var collision=Path.of(manifest.path()).toAbsolutePath().getParent();if(collision==null||collision.getParent()==null)return 0;
        var root=collision.getParent();
        if(!Files.isRegularFile(root.resolve("npc-debug"))){message("Native调试未启用：先运行tools/npc-debug.ps1 -Enable，并正常重启Sifu");return 0;}
        if(!pending.compareAndSet(false,true)){message("上一条命令仍在等待；不会自动重复生成/移动");return 0;}
        long now=WorldWriter.clockMillis(),id=ids.updateAndGet(old->Math.max(old+1,now));
        var r=new NpcDebugProtocol.Request(op,kind,id,now,v.scope().nativeSession(),v.scope().mc(),v.scope().world(),target,e==null?0:e.spawn(),
            op==NpcDebugProtocol.CLEAR?0:(float)v.placement().ueX(at.x),op==NpcDebugProtocol.CLEAR?0:(float)v.placement().ueY(at.z),op==NpcDebugProtocol.CLEAR?0:(float)v.placement().ueZ(at.y),v.scope().map(),v.scope().save());
        message("已提交调试命令 #"+id+"，等待真实Native结果");
        io.submit(()->{
            try{
                var temp=root.resolve("npc-debug.request.tmp");Files.write(temp,NpcDebugProtocol.encode(r));
                try{Files.move(temp,root.resolve("npc-debug.request"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException error){Files.move(temp,root.resolve("npc-debug.request"),StandardCopyOption.REPLACE_EXISTING);}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                while(System.nanoTime()<until&&!Thread.currentThread().isInterrupted()){
                    var file=root.resolve("npc-debug.reply");
                    if(Files.isRegularFile(file)&&Files.size(file)==NpcDebugProtocol.REPLY_BYTES){
                        var reply=NpcDebugProtocol.decode(Files.readAllBytes(file),r);
                        if(reply!=null){message(String.format(java.util.Locale.ROOT,"%s：%s id=%d/%d 实际脚底=(%.3f, %.3f, %.3f) HP=%.2f",reply.status()==NpcDebugProtocol.APPLIED?"成功":"拒绝/失败",reply.reason(),reply.target(),reply.spawn(),v.placement().mcX(reply.x()),v.placement().mcY(reply.z()),v.placement().mcZ(reply.y()),reply.health()));return;}
                    }
                    Thread.sleep(75);
                }
                message("结果超时，可能已执行；先用list核对，工具不会自动重放。确认Native已开启npc-debug并重启。");
            }catch(InterruptedException error){Thread.currentThread().interrupt();}
            catch(Exception error){message("命令通信失败："+error.getMessage()+"；如已写请求，先核对实际角色，勿盲目重发");}
            finally{pending.set(false);}
        });
        return 1;
    }
}
