package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Separate JVMs and Native endpoint sessions, one disposable saved MC world. */
final class FluidPersistenceCheck {
    private final String phase=System.getProperty("sifucraft.fluidPersistencePhase","prepare");
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private volatile boolean ready,finished;private boolean preserve;private ServerLevel level;
    private int stage,ticks;private long deadline,firstSession,savedTime,pendingDue;private Map<String,String> expected;
    FluidPersistenceCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-persistence-check-"),"disposable save required");require(phase.equals("prepare")||phase.equals("verify"),"explicit restart phase");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("fluid persistence: "+why);}
    void clientTick(Minecraft mc,boolean gui){if(finished){mc.stop();return;}var v=WorldMotionWorld.current();ready=gui&&v!=null&&v.placement().map().equals("FluidFixture")&&v.shapesCurrent()&&SceneFluidGeometry.available(mc.level);}
    private java.nio.file.Path marker(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("p9-fluid-persistence.json");}
    private void capture(int x0,int x1,int y0,int y1,int z0,int z1){for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++){var p=new BlockPos(x,y,z);var old=level.getBlockState(p);require(!old.hasBlockEntity(),"no saved BE replaced");originals.putIfAbsent(p,old);}}
    private void put(int x,int y,int z,BlockState state){var p=new BlockPos(x,y,z);var old=level.getBlockState(p);require(!old.hasBlockEntity(),"no saved BE replaced");originals.putIfAbsent(p,old);level.setBlockAndUpdate(p,state);}
    private void seed(){
        for(int offset:new int[]{0,20}){
            capture(1+offset,15+offset,62,66,1,7);capture(42+offset,55+offset,62,80,2,6);
            if(offset!=0)for(int x=2;x<=6;x++)for(int z=2;z<=6;z++){
                put(x+offset,63,z,Blocks.STONE.defaultBlockState());
                if(x==2||x==6||z==2||z==6)for(int y=64;y<=65;y++)put(x+offset,y,z,Blocks.STONE.defaultBlockState());
            }
            put(4+offset,64,4,Blocks.WATER.defaultBlockState());
            for(int x=42;x<=46;x++)for(int z=2;z<=6;z++){
                put(x+offset,63,z,Blocks.STONE.defaultBlockState());
                if(x==42||x==46||z==2||z==6)put(x+offset,64,z,Blocks.STONE.defaultBlockState());
            }
            put(44+offset,64,4,Blocks.LAVA.defaultBlockState());
            put(48+offset,63,4,Blocks.GLASS.defaultBlockState());put(48+offset,65,4,Blocks.GLASS.defaultBlockState());
            put(47+offset,64,4,Blocks.GLASS.defaultBlockState());put(49+offset,64,4,Blocks.GLASS.defaultBlockState());
            put(48+offset,64,3,Blocks.GLASS.defaultBlockState());put(48+offset,64,5,Blocks.GLASS.defaultBlockState());
            put(48+offset,64,4,Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true));
            for(int x:new int[]{50,54}){
                put(x+offset,63,4,(x==50?Blocks.SOUL_SAND:Blocks.MAGMA_BLOCK).defaultBlockState());
                for(int y=64;y<=79;y++){
                    put(x-1+offset,y,4,Blocks.GLASS.defaultBlockState());put(x+1+offset,y,4,Blocks.GLASS.defaultBlockState());
                    put(x+offset,y,3,Blocks.GLASS.defaultBlockState());put(x+offset,y,5,Blocks.GLASS.defaultBlockState());
                    put(x+offset,y,4,Blocks.WATER.defaultBlockState());
                }
                put(x+offset,80,4,Blocks.GLASS.defaultBlockState());
            }
        }
    }
    private List<String> water(int offset){var data=new ArrayList<String>();for(int x=1;x<=15;x++)for(int y=62;y<=66;y++)for(int z=1;z<=7;z++){
        var fluid=level.getFluidState(new BlockPos(x+offset,y,z));if(!fluid.isEmpty())data.add(x+","+y+","+z+":"+fluid);
    }return data;}
    private void compare(){
        require(water(0).equals(water(20)),"Native and independent saved vanilla pools agree at tick="+ticks);
        for(int x=42;x<=55;x++)for(int y=62;y<=80;y++)for(int z=2;z<=6;z++){
            var a=level.getBlockState(new BlockPos(x,y,z));var b=level.getBlockState(new BlockPos(x+20,y,z));
            require(a.equals(b),"paired lava/waterlogged/bubble state x="+x+" y="+y+" z="+z+" tick="+ticks+" actual="+a+" reference="+b);
        }
    }
    private Map<String,String> snapshot(){var data=new TreeMap<String,String>();for(var p:originals.keySet())data.put(Long.toString(p.asLong()),BlockStateKey.encode(level.getBlockState(p)));return data;}
    private void pending(MinecraftServer server){
        pendingDue=level.getGameTime()+300;
        for(int x:new int[]{90,110}){
            capture(x-1,x+1,63,64,3,5);
            for(int dx=-1;dx<=1;dx++)for(int z=3;z<=5;z++){
                put(x+dx,63,z,Blocks.STONE.defaultBlockState());
                if(dx!=0||z!=4)put(x+dx,64,z,Blocks.STONE.defaultBlockState());
            }
            var pos=new BlockPos(x,64,4);put(x,64,4,Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,4));
            level.getFluidTicks().clearArea(new net.minecraft.world.level.levelgen.structure.BoundingBox(pos));
            var fluid=level.getFluidState(pos).getType();level.scheduleTick(pos,fluid,300);
            require(level.getFluidTicks().hasScheduledTick(pos,fluid),"explicit pending original fluid tick exists before save");
        }
    }
    private void pendingState(){
        for(int x:new int[]{90,110}){
            var pos=new BlockPos(x,64,4);var state=level.getBlockState(pos);
            if(level.getGameTime()<pendingDue){
                require(state.is(Blocks.WATER)&&state.getValue(BlockStateProperties.LEVEL)==4,"saved pending liquid cannot update before its original due time");
                require(level.getFluidTicks().hasScheduledTick(pos,state.getFluidState().getType()),"saved pending fluid tick retained in the replacement JVM");
            }else if(level.getGameTime()>=pendingDue+10)require(state.isAir(),"original pending flow decays after its restored due time");
        }
    }
    private void stable(){
        require(water(0).size()==9,"saved Native pool retains source and all nine cells");
        for(int offset:new int[]{0,20}){
            require(level.getBlockState(new BlockPos(44+offset,64,4)).is(Blocks.LAVA),"lava source retained");
            require(level.getBlockState(new BlockPos(48+offset,64,4)).getValue(BlockStateProperties.WATERLOGGED),"waterlogged stair retained");
            for(int x:new int[]{50,54})for(int y:new int[]{64,79}){
                var state=level.getBlockState(new BlockPos(x+offset,y,4));require(state.is(Blocks.BUBBLE_COLUMN)&&state.getValue(BubbleColumnBlock.DRAG_DOWN)==(x==54),"original 16-high bubble column and direction retained");
            }
        }
    }
    void tick(MinecraftServer server){
        if(finished||!ready)return;
        try{
            if(stage==0){
                level=server.overworld();deadline=System.currentTimeMillis()+60000;var v=WorldMotionWorld.current();if(v==null)return;
                if(phase.equals("prepare")){require(!java.nio.file.Files.exists(marker(server)),"fresh prepare save required");firstSession=v.scope().nativeSession();seed();}
                else{
                    var data=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(marker(server))).getAsJsonObject();require(data.get("phase").getAsString().equals("prepared"),"prior JVM prepared and saved the fixture");
                    firstSession=Long.parseUnsignedLong(data.get("native_session").getAsString());savedTime=data.get("world_time").getAsLong();require(firstSession!=v.scope().nativeSession(),"independent replacement Native session");require(level.getGameTime()>=savedTime,"saved MC game time restored");
                    pendingDue=data.get("pending_fluid_due").getAsLong();
                    for(var entry:data.getAsJsonObject("originals").entrySet())originals.put(BlockPos.of(Long.parseLong(entry.getKey())),BlockStateKey.decode(entry.getValue().getAsString()));
                    expected=new TreeMap<>();for(var entry:data.getAsJsonObject("snapshot").entrySet())expected.put(entry.getKey(),entry.getValue().getAsString());
                    require(snapshot().equals(expected),"all saved block/fluid states exactly restored before continuing original ticks");stable();
                    LoggerFactory.getLogger("sifucraft").info("[fluid-persistence-test] full JVM/Native restart loaded {} exact states, saved_time={} current_time={}",expected.size(),savedTime,level.getGameTime());
                }
                stage=1;return;
            }
            require(System.currentTimeMillis()<deadline,"bounded fixture deadline");compare();if(phase.equals("verify"))pendingState();if(++ticks<(phase.equals("verify")?400:160))return;stable();
            if(phase.equals("prepare")){
                pending(server);
                var originalKeys=new TreeMap<String,String>();for(var e:originals.entrySet())originalKeys.put(Long.toString(e.getKey().asLong()),BlockStateKey.encode(e.getValue()));
                var report=new LinkedHashMap<String,Object>();report.put("phase","prepared");report.put("native_session",Long.toUnsignedString(firstSession));report.put("world_time",level.getGameTime());report.put("pending_fluid_due",pendingDue);report.put("snapshot",snapshot());report.put("originals",originalKeys);
                AtomicFiles.write(marker(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                preserve=true;server.saveEverything(false,true,true);finished=true;
                LoggerFactory.getLogger("sifucraft").info("[fluid-persistence-test] PREPARED {} exact states after 160 original ticks; fixture retained for separate JVM/Native restart",originals.size());return;
            }
            var session=WorldMotionWorld.current().scope().nativeSession();int states=expected.size();restore(server);
            AtomicFiles.write(marker(server),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("phase","verified","result","PASS","exact_states",states,"first_native_session",Long.toUnsignedString(firstSession),"next_native_session",Long.toUnsignedString(session),"continued_world_ticks",ticks,"water_lava_waterlogged_bubble16",true,"pending_original_fluid_tick",true),out));
            finished=true;LoggerFactory.getLogger("sifucraft").info("[fluid-persistence-test] PASS full JVM/Native restart: {} exact saved states, 400 continued vanilla ticks, water/lava/waterlogged and up/down 16-high columns, original pending tick due={}; restored",states,pendingDue);
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[fluid-persistence-test] FAIL",e);preserve=false;restore(server);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer ignored){if(!preserve&&level!=null)for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);if(!preserve)originals.clear();}
}
