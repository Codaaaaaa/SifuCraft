package dev.sifucraft.client;

import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.LoggerFactory;

/** Original world-ticked dispensers and TNT; no custom launch velocity or fuse. */
final class CannonCheck {
    private BlockPos origin=new BlockPos(24,200,8);
    private final boolean live=Boolean.getBoolean("sifucraft.cannonLive");
    private volatile dev.sifucraft.bridge.SifuFrame frame;private float nativeHealth;
    private final int shotLimit=Integer.getInteger("sifucraft.cannonShots",12);
    private final int firstShot=Integer.getInteger("sifucraft.cannonFirstShot",0);
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>(),body=new LinkedHashMap<>();
    private final Set<ChunkPos> forced=new HashSet<>();
    private final Set<UUID> seen=new HashSet<>();
    private final List<Map<String,Object>> reports=new ArrayList<>();
    private ServerLevel level;private boolean started,hidden,configured,finished;private int stage,tick,shot,shotTick,delay;
    private PrimedTnt charge,shell;private Vec3 shellStart,last,launchVelocity;private double peak,maxSpeed,launchY,launchPeak;
    private final long deadline=System.currentTimeMillis()+300000;

    static void install(){
        var test=attach();ClientTickEvents.END_CLIENT_TICK.register(test::clientTick);
    }
    static CannonCheck attach(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-cannon-check-"),"disposable cannon save required");
        var test=new CannonCheck();require(test.shotLimit>=1&&test.shotLimit<=12&&test.firstShot>=0&&test.firstShot<test.shotLimit,"bounded shot count");test.shot=test.firstShot;
        ServerTickEvents.END_SERVER_TICK.register(test::tick);ServerLifecycleEvents.SERVER_STOPPING.register(test::restore);return test;
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("cannon: "+why);}
    private BlockPos p(int x,int y,int z){return origin.offset(x,y,z);}
    private void put(BlockPos pos,BlockState state){originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    private int dispenserY(int x){return x==7?4:2;}
    private Container inventory(int x){return (Container)level.getBlockEntity(p(x,dispenserY(x),0));}
    private int stock(){int n=0;for(int x:new int[]{1,7})for(int i=0;i<inventory(x).getContainerSize();i++){var s=inventory(x).getItem(i);if(s.is(Items.TNT))n+=s.getCount();}return n;}
    private void power(int x,boolean on){put(p(x,dispenserY(x),1),(on?Blocks.REDSTONE_BLOCK:Blocks.AIR).defaultBlockState());}
    private void setup(){
        for(int x=0;x<=8;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++){
            var at=p(x,y,z);require(!level.getBlockState(at).hasBlockEntity(),"no existing container replaced");
            put(at,(y==0||y==1&&(z!=0||x==0)?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState());
        }
        put(p(7,1,0),Blocks.OBSIDIAN.defaultBlockState());
        put(p(1,1,0),Blocks.WATER.defaultBlockState());
        for(int x:new int[]{1,7}){
            put(p(x,dispenserY(x),0),Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.DOWN));
            inventory(x).setItem(0,new ItemStack(Items.TNT,shotLimit-firstShot));
        }
        for(var at:originals.keySet())if(!level.getBlockState(at).isAir()&&level.getFluidState(at).isEmpty())body.put(at,level.getBlockState(at));
        configured=true;
    }
    private void clientTick(Minecraft mc){
        if(finished){mc.stop();return;}require(System.currentTimeMillis()<deadline,"bounded world fixture");mc.options.pauseOnLostFocus=false;
        if(live)return;
        if(!hidden){SDLVideo.SDL_HideWindow(mc.getWindow().handle());hidden=true;}
        if(!started&&mc.level==null&&mc.gui.overlay()==null&&mc.gui.screen()!=null){started=true;if(mc.options.onboardAccessibility)mc.options.onboardingAccessibilityFinished();DevWorld.enter(mc);}
    }
    void clientTick(Minecraft mc,dev.sifucraft.bridge.SifuFrame snapshot){frame=snapshot;clientTick(mc);}
    private void tick(MinecraftServer server){
        if(finished||server.getPlayerList().getPlayers().isEmpty())return;
        try{
            require(System.currentTimeMillis()<deadline,"bounded server fixture");level=server.overworld();
            if(stage==0){
                if(live){
                    var view=dev.sifucraft.blocks.WorldMotionWorld.current();
                    if(view==null||view.nativeStatus()==null||view.nativeStatus().flags()!=0||!view.shapesCurrent()||frame==null||!frame.hasPawn()||!frame.hasCamera()||view.placement().map().equals("MotionFixture"))return;
                    // An elevated, disposable platform keeps both native characters
                    // and the player's ordinary save outside all blast radii.
                    var at=frame.playerLocation();origin=BlockPos.containing(view.placement().mcX(at.x())+24,view.placement().originY()+64,view.placement().mcZ(at.y())+8);nativeHealth=frame.health();
                }
                // Tick every possible shot chunk normally; never manually advance a TNT entity.
                for(int x=-1;x<=10;x++)for(int z=-2;z<=2;z++){var c=new ChunkPos((origin.getX()>>4)+x,(origin.getZ()>>4)+z);if(level.setChunkForced(c.x(),c.z(),true))forced.add(c);}
                stage=1;return;
            }
            if(live){require(frame.health()==nativeHealth,"native player remains outside the blast");if(stage==1){var v=dev.sifucraft.blocks.WorldMotionWorld.current();if(v==null||!v.shapesCurrent())return;}}
            if(stage==1){if(!level.isPositionEntityTicking(origin))return;setup();stage=2;tick=0;return;}
            if(stage==2){if(++tick<100)return;require(stock()==(shotLimit-firstShot)*2,"initial ammunition");beginShot();return;}
            shotTick++;
            if(shotTick==6)power(1,false);
            if(shotTick==delay)power(7,true);
            if(shotTick==delay+6)power(7,false);
            for(var entity:level.getAllEntities())if(entity instanceof PrimedTnt tnt&&!seen.contains(tnt.getUUID())){
                require(tnt.position().distanceToSqr(Vec3.atCenterOf(p(4,1,0)))<100,"only this cannon produces TNT");
                seen.add(tnt.getUUID());require(tnt.getFuse()>=78&&tnt.getFuse()<=80,"original dispenser fuse");
                if(charge==null)charge=tnt;else {require(shell==null,"one charge and one shell per shot");shell=tnt;shellStart=tnt.position();last=shellStart;peak=shell.getY();}
            }
            if(shell!=null&&!shell.isRemoved()){
                if(charge!=null&&charge.isRemoved()&&launchVelocity==null){launchY=last.y;launchPeak=launchY;launchVelocity=shell.getDeltaMovement();}
                if(launchVelocity!=null)launchPeak=Math.max(launchPeak,shell.getY());
                last=shell.position();peak=Math.max(peak,shell.getY());maxSpeed=Math.max(maxSpeed,shell.getDeltaMovement().length());
            }
            require(shotTick<240,"shot finishes without frozen or lost TNT");
            if(charge==null||shell==null||!charge.isRemoved()||!shell.isRemoved())return;
            require(stock()==2*(shotLimit-shot-1),"two dispenser items consumed for two TNT UUIDs");
            require(last.x>shellStart.x+2&&launchVelocity!=null&&launchVelocity.x>.1&&launchVelocity.y>.01&&launchPeak>launchY+.05,"original charge launches the shell in XYZ: start="+shellStart+" end="+last+" rise="+(launchPeak-launchY)+" launch="+launchVelocity);
            for(var e:body.entrySet())require(level.getBlockState(e.getKey()).getBlock()==e.getValue().getBlock(),"water-cooled cannon body survives at "+e.getKey());
            require(level.getFluidState(p(1,1,0)).isSource(),"cooling water source survives");
            var report=new LinkedHashMap<String,Object>();report.put("shot",shot+1);report.put("delay_ticks",delay);report.put("duration_ticks",shotTick);report.put("charge_uuid",charge.getUUID());report.put("shell_uuid",shell.getUUID());report.put("shell_start",List.of(shellStart.x,shellStart.y,shellStart.z));report.put("launch_y",launchY);report.put("launch_velocity",List.of(launchVelocity.x,launchVelocity.y,launchVelocity.z));report.put("last_before_explosion",List.of(last.x,last.y,last.z));report.put("peak_y",peak);report.put("max_speed_m_per_tick",maxSpeed);report.put("remaining_tnt",stock());reports.add(report);
            LoggerFactory.getLogger("sifucraft").info("[cannon-test] shot={} delay={} duration={} last={} peak={} stock={}",shot+1,delay,shotTick,last,peak,stock());
            if(++shot<shotLimit){beginShot();return;}
            require(seen.size()==(shotLimit-firstShot)*2,"unique TNT production and complete consumption");
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p10-cannon.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"backend",live?"real Sifu bridge and geometry ACK; elevated MC test platform, NPC blast tested separately":"installed original MC world ticks; production mixins, no native bridge","shots",reports,"unique_tnt",seen.size()),out));
            LoggerFactory.getLogger("sifucraft").info("[cannon-test] PASS executed_shots={} unique_tnt={} normal dispenser, fuse, water cooling and launch live={}",reports.size(),seen.size(),live);restore(server);finished=true;
        }catch(Exception|AssertionError error){restore(server);finished=true;LoggerFactory.getLogger("sifucraft").error("[cannon-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    private void beginShot(){delay=shot<10?30:shot==10?20:40;shotTick=0;charge=null;shell=null;launchVelocity=null;maxSpeed=0;power(1,true);stage=3;LoggerFactory.getLogger("sifucraft").info("[cannon-test] armed shot={} delay={} origin={} live={}",shot+1,delay,origin,live);}
    private void restore(MinecraftServer server){
        if(level==null)return;if(charge!=null&&!charge.isRemoved())charge.discard();if(shell!=null&&!shell.isRemoved())shell.discard();
        if(configured){for(var at:originals.keySet())level.removeBlockEntity(at);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}
        for(var c:forced)level.setChunkForced(c.x(),c.z(),false);forced.clear();
    }
}
