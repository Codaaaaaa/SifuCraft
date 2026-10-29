package dev.sifucraft.combat;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.*;

/** Credentials for original periodic effect ticks; never accepts an arbitrary magic DamageSource. */
public final class MachineEffects {
    private MachineEffects(){}
    private static final class Bound {
        final Map<Holder<MobEffect>,MachineProjectiles.Origin> origins=new HashMap<>();
        String key;boolean restored;
    }
    private static final Map<EnemyProxy,Bound> bound=new IdentityHashMap<>();
    private static final Map<String,String> saved=new TreeMap<>();
    private static final ThreadLocal<EnemyProxy> ticking=new ThreadLocal<>();
    private static final ThreadLocal<MachineProjectiles.Origin> restoring=new ThreadLocal<>();
    private static Path file;private static boolean dirty;private static long nextWrite;
    private static boolean supported(Holder<MobEffect> type){return type.equals(MobEffects.POISON)||type.equals(MobEffects.WITHER);}
    private static String key(EnemyProxy proxy){
        var v=WorldMotionWorld.current();if(v==null||!(proxy.level() instanceof ServerLevel)||proxy.sifuKey().id()<0)return null;
        var s=v.scope();return Long.toUnsignedString(s.nativeSession())+":"+s.world()+":"+s.save()+":"+s.map()+":"+proxy.sifuKey().id()+":"+proxy.sifuKey().spawn();
    }
    public static void open(MinecraftServer server){
        bound.clear();saved.clear();dirty=false;nextWrite=0;file=server.getWorldPath(LevelResource.ROOT).resolve("sifucraft-machine-effects.json");
        try{if(Files.exists(file)&&Files.size(file)<=4*1024*1024){var root=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(root.size()>1024)throw new IllegalArgumentException("effect binding capacity");for(var e:root.entrySet())saved.put(e.getKey(),e.getValue().getAsString());}}
        catch(Exception error){saved.clear();org.slf4j.LoggerFactory.getLogger("sifucraft").warn("[machine-effects] cannot restore credentials",error);}
    }
    public static void save(MinecraftServer server){if(System.currentTimeMillis()<nextWrite)return;write();nextWrite=System.currentTimeMillis()+1000;}
    public static void flush(MinecraftServer server){for(var proxy:List.copyOf(bound.keySet()))snapshot(proxy);write();}
    private static void write(){if(!dirty||file==null)return;try{AtomicFiles.write(file,out->new com.google.gson.Gson().toJson(saved,out));dirty=false;}catch(java.io.IOException error){org.slf4j.LoggerFactory.getLogger("sifucraft").warn("[machine-effects] credential save failed",error);}}
    static void added(EnemyProxy proxy,MobEffectInstance effect,Entity source,boolean applied){
        if(!(proxy.level() instanceof ServerLevel)||!supported(effect.getEffect()))return;
        var state=bound.computeIfAbsent(proxy,ignored->new Bound());var origin=restoring.get();if(origin==null)origin=MachineProjectiles.effectOrigin(source,proxy);
        // A rejected/hidden or uncredentialed competing layer cannot inherit an
        // earlier machine's authority. Ordinary effect application itself stays vanilla.
        if(applied&&origin!=null)state.origins.put(effect.getEffect(),origin);else state.origins.remove(effect.getEffect());
        snapshot(proxy);
    }
    static void tick(EnemyProxy proxy,Runnable vanilla){
        if(!(proxy.level() instanceof ServerLevel)){vanilla.run();return;}
        if(!WorldEffects.effectsReady(proxy))return;
        restore(proxy);var old=ticking.get();ticking.set(proxy);
        try{vanilla.run();}finally{if(old==null)ticking.remove();else ticking.set(old);snapshot(proxy);}
    }
    static void prepare(EnemyProxy proxy){if(proxy.level() instanceof ServerLevel)restore(proxy);}
    static MachineProjectiles.Origin evidence(EnemyProxy proxy,DamageSource source){
        if(ticking.get()!=proxy||source.getEntity()!=null||source.getDirectEntity()!=null)return null;
        var type=source.is(DamageTypes.MAGIC)?MobEffects.POISON:source.is(DamageTypes.WITHER)?MobEffects.WITHER:null;
        var state=bound.get(proxy);if(type==null||state==null||!proxy.hasEffect(type)||!Objects.equals(state.key,key(proxy)))return null;
        var origin=state.origins.get(type);var v=WorldMotionWorld.current();
        return origin!=null&&v!=null&&origin.save()==v.scope().save()&&origin.map()==v.scope().map()?origin:null;
    }
    private static void restore(EnemyProxy proxy){
        var state=bound.computeIfAbsent(proxy,ignored->new Bound());String wanted=key(proxy);if(wanted==null)return;
        if(state.key!=null&&!state.key.equals(wanted)){state.origins.clear();state.restored=false;}
        state.key=wanted;if(state.restored)return;state.restored=true;String text=saved.get(wanted);if(text==null)return;
        try{var level=(ServerLevel)proxy.level();var input=TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),TagParser.parseCompoundFully(text));
            for(var entry:input.childrenListOrEmpty("effects")){
                var origin=MachineProjectiles.load(entry);var effect=entry.read("effect",MobEffectInstance.CODEC).orElse(null);
                if(origin==null||effect==null||!supported(effect.getEffect()))continue;
                restoring.set(origin);try{proxy.addEffect(effect);}finally{restoring.remove();}
            }
        }catch(Exception error){saved.remove(wanted);dirty=true;org.slf4j.LoggerFactory.getLogger("sifucraft").warn("[machine-effects] invalid saved credential rejected",error);}
    }
    private static void snapshot(EnemyProxy proxy){
        var state=bound.get(proxy);if(state==null||!(proxy.level() instanceof ServerLevel level))return;
        String wanted=key(proxy);if(wanted==null)return;
        if(state.key!=null&&!state.key.equals(wanted))return;state.key=wanted;
        state.origins.entrySet().removeIf(e->!proxy.hasEffect(e.getKey()));
        if(state.origins.isEmpty()){if(saved.remove(wanted)!=null)dirty=true;return;}
        if(!saved.containsKey(wanted)&&saved.size()>=1024)return;
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());var list=out.childrenList("effects");
        state.origins.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().unwrapKey().orElseThrow().identifier().toString())).forEach(e->{var child=list.addChild();MachineProjectiles.save(e.getValue(),child);child.store("effect",MobEffectInstance.CODEC,new MobEffectInstance(proxy.getEffect(e.getKey())));});
        String text=out.buildResult().toString();if(!text.equals(saved.put(wanted,text)))dirty=true;
    }
    static void detached(EnemyProxy proxy){if(proxy.level() instanceof ServerLevel){snapshot(proxy);bound.remove(proxy);}}
}
