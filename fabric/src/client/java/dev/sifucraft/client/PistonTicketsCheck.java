package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonActions;
import dev.sifucraft.blocks.PistonActionTickets;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Real remote chunks: held actions, shared tickets, expiry, disk unload and NBT recovery. */
public final class PistonTicketsCheck {
    private static PistonTicketsCheck current;
    private static final int X=15998;
    private static final List<ChunkPos> CHUNKS=List.of(new ChunkPos(999,0),new ChunkPos(1000,0));
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private ServerLevel level;private CompletableFuture<?> loading;private volatile boolean finished;private boolean holding,pinned,preserve;private int stage;
    private final String restart=System.getProperty("sifucraft.pistonTicketsRestart","off");
    private long deadline,heldAt,nextLog;private Set<PistonActions.Token> saved;private PistonActionTickets.Stats baseline;
    PistonTicketsCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-piston-tickets-check-"),"disposable save required");require(Set.of("off","prepare","verify").contains(restart),"known restart phase");current=this;}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston tickets: "+why);}
    public static boolean hold(Level level,BlockPos pos){var c=current;return c!=null&&c.holding&&level==c.level&&pos.getX()>=X&&pos.getX()<=X+3&&pos.getZ()>=3&&pos.getZ()<=13;}
    void clientTick(Minecraft mc){if(finished)mc.stop();}
    private BlockPos base(int z){return new BlockPos(X,65,z);}
    private void load(){pinned=true;loading=CompletableFuture.allOf(CHUNKS.stream().map(c->level.getChunkSource().addTicketAndLoadWithRadius(PistonActionTickets.fixtureType(),c,2)).toArray(CompletableFuture[]::new));}
    private void unpin(){if(pinned){for(var c:CHUNKS)level.getChunkSource().removeTicketWithRadius(PistonActionTickets.fixtureType(),c,2);pinned=false;}}
    private boolean loaded(){if(!loading.isDone())return false;require(!loading.isCompletedExceptionally(),"fixture loading succeeded");return CHUNKS.stream().allMatch(c->level.getChunkSource().getChunkNow(c.x(),c.z())!=null);}
    private boolean unloaded(){return CHUNKS.stream().allMatch(c->level.getChunkSource().getChunkNow(c.x(),c.z())==null);}
    private void set(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"does not replace a saved BE");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private void start(int... lanes){holding=true;for(int z:lanes){var base=base(z);for(var pos:BlockPos.betweenClosed(base.offset(-1,-1,-1),base.offset(3,-1,1)))set(pos,Blocks.STONE.defaultBlockState());set(base,Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST));set(base.east(),Blocks.STONE.defaultBlockState());set(base.below(),Blocks.REDSTONE_BLOCK.defaultBlockState());}}
    private Set<PistonActions.Token> moving(int... lanes){
        var tokens=new HashSet<PistonActions.Token>();
        for(int z:lanes)for(int x=1;x<=2;x++){
            if(!(level.getBlockEntity(base(z).east(x)) instanceof PistonMovingBlockEntity p))return Set.of();
            var member=PistonActions.member(p);if(member==null)return Set.of();require(p.getProgress(1)==0,"diagnostic hold keeps original BE at zero");tokens.add(member.token());
        }return tokens;
    }
    private boolean finalState(int... lanes){for(int z:lanes){var base=base(z);if(!level.getBlockState(base).is(Blocks.PISTON)||!level.getBlockState(base).getValue(PistonBaseBlock.EXTENDED)||!level.getBlockState(base.east(2)).is(Blocks.STONE)||level.getBlockEntity(base.east()) instanceof PistonMovingBlockEntity||level.getBlockEntity(base.east(2)) instanceof PistonMovingBlockEntity)return false;}return true;}
    private String tokenText(PistonActions.Token token){return token.id()+"@"+token.base().asLong()+":"+token.positions().stream().map(p->Long.toString(p.asLong())).collect(java.util.stream.Collectors.joining(","));}
    private java.nio.file.Path restartFile(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("p8-piston-restart.json");}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            if(stage==0){var v=WorldMotionWorld.current();if(v==null||!v.shapesCurrent()||!v.placement().map().equals("MotionFixture"))return;level=server.overworld();deadline=System.currentTimeMillis()+90000;baseline=PistonActionTickets.stats();require(baseline.groups()==0&&baseline.chunks()==0,"no preexisting action owner");holding=restart.equals("verify");load();stage=1;return;}
            require(System.currentTimeMillis()<deadline,"deadline stage="+stage+" stats="+PistonActionTickets.stats());
            if(System.currentTimeMillis()>=nextLog){nextLog=System.currentTimeMillis()+5000;LoggerFactory.getLogger("sifucraft").info("[piston-tickets-test] stage={} time={} stats={} fixture_pin={}",stage,level.getGameTime(),PistonActionTickets.stats(),pinned);}
            if(stage==1){if(!loaded())return;
                if(restart.equals("verify")){
                    var data=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(restartFile(server))).getAsJsonObject();require(data.get("phase").getAsString().equals("prepared"),"separate prior process prepared the saved action");
                    var expected=new HashSet<String>();for(var token:data.getAsJsonArray("tokens"))expected.add(token.getAsString());saved=moving(4,8);
                    require(saved.size()==2,"saved action metadata exists after full process restart");require(saved.stream().map(this::tokenText).collect(java.util.stream.Collectors.toSet()).equals(expected),"full process restart preserves exact action UUID/base/member order");
                    preserve=true;holding=false;unpin();stage=11;return;
                }
                start(4,8);stage=2;return;
            }
            if(stage==2){saved=moving(4,8);if(saved.size()!=2)return;require(PistonActionTickets.stats().groups()==2&&PistonActionTickets.stats().chunks()==2,"two groups share exactly two owned chunk tickets");
                if(restart.equals("prepare")){
                    AtomicFiles.write(restartFile(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("phase","prepared","tokens",saved.stream().map(this::tokenText).toList()),out));
                    preserve=true;finished=true;LoggerFactory.getLogger("sifucraft").info("[piston-tickets-test] PREPARED mid-action original BEs for normal save/complete process restart");return;
                }
                unpin();heldAt=level.getGameTime();stage=3;return;
            }
            if(stage==3){if(level.getGameTime()-heldAt<5)return;require(CHUNKS.stream().allMatch(c->level.areEntitiesActuallyLoadedAndTicking(c)),"production action tickets keep remote chunks ticking");require(moving(4,8).equals(saved),"held remote groups/progress intact");if(level.getGameTime()-heldAt<60)return;holding=false;stage=4;return;}
            if(stage==4){if(!finalState(4,8))return;require(PistonActionTickets.stats().groups()==0&&PistonActionTickets.stats().chunks()==0,"last shared owners released");stage=5;return;}
            if(stage==5){if(!unloaded())return;load();stage=6;return;}
            if(stage==6){if(!loaded())return;require(finalState(4,8),"completed layout survived actual unload/reload");start(10,12);stage=7;return;}
            if(stage==7){saved=moving(10,12);if(saved.size()!=2)return;unpin();stage=8;return;}
            if(stage==8){if(PistonActionTickets.stats().expired()<baseline.expired()+2||!unloaded())return;require(PistonActionTickets.stats().groups()==0&&PistonActionTickets.stats().chunks()==0,"jam pins expire and chunks really unload");load();stage=9;return;}
            if(stage==9){if(!loaded())return;var recovered=moving(10,12);if(recovered.size()!=2)return;require(recovered.equals(saved),"original mid-action NBT/group tokens survive unload/reload");holding=false;unpin();stage=10;return;}
            if(stage==10){if(!finalState(10,12))return;require(finalState(4,8),"older completed groups did not replay");var stats=PistonActionTickets.stats();require(stats.groups()==0&&stats.chunks()==0,"all owned tickets drained");restore(server);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-piston-tickets.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","remote_chunk_x",999,"held_ticks",60,"completed_unload_reload",true,"expired_midaction_unload_reload",true,"tokens_preserved",true,"stats",stats),out));
                LoggerFactory.getLogger("sifucraft").info("[piston-tickets-test] PASS remote shared ticking, 60tick hold, bounded jam expiry, actual chunk unload/reload and original mid-action NBT; {}",stats);finished=true;
            }
            if(stage==11){if(!finalState(4,8))return;var stats=PistonActionTickets.stats();require(stats.groups()==0&&stats.chunks()==0,"restarted action releases new process tickets after completing exactly once");
                AtomicFiles.write(restartFile(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("phase","verified","result","PASS","tokens",saved.stream().map(this::tokenText).toList(),"stats",stats,"fixture","completed remote layout retained in disposable save for inspection"),out));
                LoggerFactory.getLogger("sifucraft").info("[piston-tickets-test] PASS full process restart: exact saved action identities, original final layout and no remaining tickets; {}",stats);finished=true;
            }
        }catch(Exception|AssertionError error){LoggerFactory.getLogger("sifucraft").error("[piston-tickets-test] FAIL",error);restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer ignored){holding=false;unpin();if(!preserve&&level!=null)for(var entry:originals.entrySet()){level.removeBlockEntity(entry.getKey());level.setBlock(entry.getKey(),entry.getValue(),2);}originals.clear();current=null;}
}
