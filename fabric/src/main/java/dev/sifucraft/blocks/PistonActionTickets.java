package dev.sifucraft.blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Short, non-persistent chunk ownership for one loaded vanilla action group. */
public final class PistonActionTickets {
    private PistonActionTickets(){}
    private static TicketType type,fixture;
    private static final int RADIUS=2;
    private static final class Lease {
        final Set<ChunkPos> chunks;final long until;long refresh;boolean released;
        Lease(PistonActions.Token token,long now){
            chunks=new HashSet<>();chunks.add(ChunkPos.containing(token.base()));for(var pos:token.positions())chunks.add(ChunkPos.containing(pos));
            until=now+100;refresh=now;
        }
    }
    private static final Map<ServerLevel,Map<PistonActions.Token,Lease>> leases=new IdentityHashMap<>();
    private static final Map<ServerLevel,Map<ChunkPos,Integer>> references=new IdentityHashMap<>();
    private static long added,removed,expired;
    public record Stats(int groups,int chunks,long added,long removed,long expired){}
    public static Stats stats(){return new Stats(leases.values().stream().mapToInt(m->(int)m.values().stream().filter(l->!l.released).count()).sum(),references.values().stream().mapToInt(Map::size).sum(),added,removed,expired);}
    public static void register(){
        if(type==null)type=Registry.register(BuiltInRegistries.TICKET_TYPE,Identifier.fromNamespaceAndPath("sifucraft","piston_action"),new TicketType(40,TicketType.FLAG_LOADING|TicketType.FLAG_SIMULATION));
        if(Boolean.getBoolean("sifucraft.pistonTicketsSelfTest")&&fixture==null)fixture=Registry.register(BuiltInRegistries.TICKET_TYPE,Identifier.fromNamespaceAndPath("sifucraft","piston_ticket_fixture"),new TicketType(200,TicketType.FLAG_LOADING|TicketType.FLAG_SIMULATION));
    }
    public static TicketType fixtureType(){if(fixture==null)throw new IllegalStateException("isolated ticket fixture not enabled");return fixture;}
    public static void hold(ServerLevel level,PistonActions.Token token){
        if(type==null||!WorldBlocks.ENABLED||!level.getServer().isSameThread())return;
        long now=level.getGameTime();var group=leases.computeIfAbsent(level,k->new HashMap<>());var lease=group.get(token);
        if(lease==null){
            lease=new Lease(token,now);group.put(token,lease);var refs=references.computeIfAbsent(level,k->new HashMap<>());
            for(var chunk:lease.chunks){if(refs.merge(chunk,1,Integer::sum)==1){level.getChunkSource().addTicketWithRadius(type,chunk,RADIUS);added++;}}
        }
        if(lease.released)return;
        if(now>=lease.until){release(level,lease);expired++;return;}
        if(now>=lease.refresh){for(var chunk:lease.chunks)level.getChunkSource().addTicketWithRadius(type,chunk,RADIUS);lease.refresh=now+10;}
    }
    private static void release(ServerLevel level,Lease lease){
        if(lease.released)return;lease.released=true;var refs=references.get(level);
        for(var chunk:lease.chunks){int count=refs.getOrDefault(chunk,0);if(count>1)refs.put(chunk,count-1);else if(count==1){refs.remove(chunk);level.getChunkSource().removeTicketWithRadius(type,chunk,RADIUS);removed++;}}
        if(refs.isEmpty())references.remove(level);
    }
    public static void tick(MinecraftServer server){
        var live=PistonActions.loadedTokens();
        for(var entry:live.entrySet())for(var token:entry.getValue())hold(entry.getKey(),token);
        var levels=leases.entrySet().iterator();while(levels.hasNext()){
            var entry=levels.next();var present=live.getOrDefault(entry.getKey(),Set.of());
            var actions=entry.getValue().entrySet().iterator();while(actions.hasNext()){
                var action=actions.next();if(!present.contains(action.getKey())){release(entry.getKey(),action.getValue());actions.remove();}
            }
            if(entry.getValue().isEmpty())levels.remove();
        }
    }
    public static void stop(){for(var entry:leases.entrySet())for(var lease:entry.getValue().values())release(entry.getKey(),lease);leases.clear();references.clear();}
}
