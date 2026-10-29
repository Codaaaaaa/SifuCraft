package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Isolated check of the real Sifu menu -> vanilla integrated-server pause path. */
final class NativePauseCheck {
    private volatile int phase;private volatile long worldTime,pausedAt;private volatile boolean finished;
    NativePauseCheck(){if(!DevWorld.LEVEL_ID.startsWith("sifucraft-native-pause-check-"))throw new IllegalStateException("disposable save required");}
    void tick(MinecraftServer server){
        if(phase==0&&WorldMotionWorld.current()!=null){phase=1;LoggerFactory.getLogger("sifucraft").info("[native-pause-test] READY normal world tick");}
        if(phase==3){
            long elapsed=System.currentTimeMillis()-pausedAt,delta=server.overworld().getGameTime()-worldTime;
            if(elapsed<1000||delta>1)throw new AssertionError("Native pause elapsed="+elapsed+" gameTime delta="+delta);
            try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-native-pause.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","pause_ms",elapsed,"world_tick_delta",delta,"real_native_pause",true),out));}catch(Exception e){throw new IllegalStateException(e);}
            phase=4;finished=true;LoggerFactory.getLogger("sifucraft").info("[native-pause-test] PASS actual Native pause {}ms, world delta {}",elapsed,delta);
        }
    }
    void clientTick(Minecraft mc){
        if(finished){mc.stop();return;}
        var view=WorldMotionWorld.current();if(view==null||view.nativeStatus()==null)return;
        boolean nativePause=(view.nativeStatus().flags()&1)!=0;
        if(phase==1&&nativePause&&mc.isPaused()){
            phase=2;var server=mc.getSingleplayerServer();server.execute(()->{worldTime=server.overworld().getGameTime();pausedAt=System.currentTimeMillis();LoggerFactory.getLogger("sifucraft").info("[native-pause-test] PAUSED worldTime={} clock={}",worldTime,pausedAt);});
        }
        if(phase==2&&pausedAt!=0&&!nativePause&&!mc.isPaused())phase=3;
    }
}
