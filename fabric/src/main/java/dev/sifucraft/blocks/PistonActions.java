package dev.sifucraft.blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Creation-order membership of one real vanilla piston block event, including its source head. */
public final class PistonActions {
    private PistonActions(){}
    public record Token(UUID id,BlockPos base,List<BlockPos> positions){public Token{base=base.immutable();positions=List.copyOf(positions);}}
    public record Member(Token token,int ordinal){}
    private static final class Capture {final UUID id=UUID.randomUUID();final ServerLevel level;final BlockPos base;final List<PistonMovingBlockEntity> created=new ArrayList<>();Capture(ServerLevel level,BlockPos base){this.level=level;this.base=base.immutable();}}
    private static final ThreadLocal<Capture> active=new ThreadLocal<>();
    private static final WeakHashMap<PistonMovingBlockEntity,Member> members=new WeakHashMap<>();
    public static boolean event(Level level,BlockPos pos,BooleanSupplier original){
        if(!WorldBlocks.ENABLED||!(level instanceof ServerLevel server))return original.getAsBoolean();
        var previous=active.get();var capture=new Capture(server,pos);active.set(capture);
        try{return original.getAsBoolean();}
        finally{
            if(previous==null)active.remove();else active.set(previous);
            var created=capture.created.stream().filter(p->p.getLevel()==server&&server.getBlockEntity(p.getBlockPos())==p&&!p.isRemoved()).toList();
            if(!created.isEmpty()){
                var token=new Token(capture.id,capture.base,created.stream().map(p->p.getBlockPos().immutable()).toList());
                synchronized(members){for(int i=0;i<created.size();i++)members.put(created.get(i),new Member(token,i));}
                PistonActionTickets.hold(server,token);
            }
        }
    }
    public static void created(BlockEntity entity){var capture=active.get();if(capture!=null&&entity instanceof PistonMovingBlockEntity moving){if(capture.created.size()>=64)throw new IllegalStateException("piston action membership capacity");capture.created.add(moving);}}
    public static Member member(PistonMovingBlockEntity piston){synchronized(members){return members.get(piston);}}
    public static java.util.Set<UUID> liveTokens(){synchronized(members){return members.values().stream().map(m->m.token.id).collect(java.util.stream.Collectors.toSet());}}
    public static java.util.Map<ServerLevel,java.util.Set<Token>> loadedTokens(){
        var result=new java.util.IdentityHashMap<ServerLevel,java.util.Set<Token>>();
        synchronized(members){for(var entry:members.entrySet()){
            var piston=entry.getKey();if(piston.isRemoved()||!(piston.getLevel() instanceof ServerLevel level))continue;
            var pos=piston.getBlockPos();var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            // Level.getBlockEntity can renew an implicit UNKNOWN ticket. A
            // cleanup/readback must not keep an expired action's chunk loaded.
            if(chunk==null||chunk.getBlockEntity(pos)!=piston)continue;
            result.computeIfAbsent(level,k->new java.util.HashSet<>()).add(entry.getValue().token);
        }}return result;
    }
    public static List<PistonMovingBlockEntity> members(ServerLevel level,Member member){
        PistonActionTickets.hold(level,member.token);
        var result=new ArrayList<PistonMovingBlockEntity>();
        // Chunk loading is staggered. Never let the first loaded member advance
        // while another saved member's chunk is still unavailable.
        if(member.token.positions.stream().anyMatch(pos->!level.hasChunkAt(pos)))return List.of();
        for(var pos:member.token.positions)if(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston){var recorded=member(piston);if(recorded!=null&&recorded.token.equals(member.token))result.add(piston);}
        return List.copyOf(result);
    }
    public static void save(PistonMovingBlockEntity piston,ValueOutput output){
        var member=member(piston);if(member==null)return;var out=output.child("sifucraft_piston_action");var token=member.token;
        out.putLong("hi",token.id.getMostSignificantBits());out.putLong("lo",token.id.getLeastSignificantBits());out.putLong("base",token.base.asLong());out.putInt("ordinal",member.ordinal);
        out.store("positions",com.mojang.serialization.Codec.LONG.listOf(),token.positions.stream().map(BlockPos::asLong).toList());
    }
    public static void load(PistonMovingBlockEntity piston,ValueInput input){
        var in=input.childOrEmpty("sifucraft_piston_action");long hi=in.getLongOr("hi",0),lo=in.getLongOr("lo",0);int ordinal=in.getIntOr("ordinal",-1);
        var positions=in.read("positions",com.mojang.serialization.Codec.LONG.listOf()).orElse(List.of());
        if((hi|lo)==0||positions.isEmpty()||positions.size()>64||ordinal<0||ordinal>=positions.size()||positions.stream().distinct().count()!=positions.size()||!BlockPos.of(positions.get(ordinal)).equals(piston.getBlockPos()))return;
        var base=BlockPos.of(in.getLongOr("base",0));var blocks=positions.stream().map(BlockPos::of).toList();
        if(blocks.stream().anyMatch(p->Math.abs((long)p.getX()-base.getX())>16||Math.abs((long)p.getY()-base.getY())>16||Math.abs((long)p.getZ()-base.getZ())>16))return;
        var token=new Token(new UUID(hi,lo),base,blocks);synchronized(members){members.put(piston,new Member(token,ordinal));}
    }
    public static void stop(){PistonActionTickets.stop();active.remove();synchronized(members){members.clear();}}
}
