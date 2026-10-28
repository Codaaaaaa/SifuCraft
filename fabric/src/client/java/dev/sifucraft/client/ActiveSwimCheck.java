package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.client.mixin.KeyMappingAccessor;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Actual LocalPlayer keyboard swimming on native ground, not a synthetic Player prediction body. */
final class ActiveSwimCheck {
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private volatile SifuFrame frame;
    private volatile boolean finished,ready,release;private volatile int stage;private int warmup,lastTick=-1,samples,swimming,underwater;private boolean armed;
    private volatile double moved,raised;private volatile float clientHp;private ServerLevel level;private ServerPlayer player;
    private Vec3 original;private float yaw,pitch,hp,nativeHp;private int air;private long began=System.currentTimeMillis();
    ActiveSwimCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-active-swim-check-"),"disposable native swimming world");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("actual active swim: "+why);}
    private void key(InputInjector input,KeyMapping binding,boolean down){var code=((KeyMappingAccessor)binding).sifucraft$key();require(code.getType()==com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,"keyboard input binding");input.key(code.getValue(),down?InputInjector.PRESS:InputInjector.RELEASE,0);}
    void clientTick(Minecraft mc,InputInjector input,SifuFrame snapshot){
        frame=snapshot;if(release){input.releaseAll();release=false;}
        if(finished){input.releaseAll();mc.stop();return;}
        var v=WorldMotionWorld.current();ready=mc.player!=null&&mc.gui.screen()==null&&snapshot!=null&&snapshot.minecraftOwnsInput()&&v!=null&&v.shapesCurrent()&&SceneFluidGeometry.available(mc.level);
        if(mc.player==null)return;clientHp=mc.player.getHealth();
        if(stage!=2)return;
        if(!armed){require(ready,"MC owns real player input");input.releaseAll();mc.player.setYRot(-90);mc.player.setXRot(0);key(input,mc.options.keyUp,true);key(input,mc.options.keySprint,true);armed=true;}
        if(lastTick==mc.player.tickCount)return;lastTick=mc.player.tickCount;samples++;
        if(mc.player.isSwimming())swimming++;if(mc.player.isUnderWater())underwater++;
        moved=Math.max(moved,mc.player.position().subtract(original).horizontalDistance());raised=Math.max(raised,mc.player.getY()-original.y);
        require(!mc.player.getAbilities().flying&&clientHp==hp,"ordinary survival movement with unchanged HP");
        if(samples==60){key(input,mc.options.keyUp,false);key(input,mc.options.keySprint,false);key(input,mc.options.keyJump,true);}
        if(samples>=85){input.releaseAll();stage=3;}
    }
    private void put(BlockPos pos,BlockState state){require(!level.getBlockState(pos).hasBlockEntity(),"no saved BE touched");originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,state);}
    void tick(MinecraftServer server){if(finished)return;try{
        require(System.currentTimeMillis()-began<180000,"bounded actual client test");var view=WorldMotionWorld.current();if(!ready||view==null||frame==null||view.placement().map().equals("MotionFixture"))return;
        if(stage==0){
            level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();if(!player.onGround())return;
            original=player.position();yaw=player.getYRot();pitch=player.getXRot();hp=player.getHealth();nativeHp=frame.health();air=player.getAirSupply();
            require(player.isAlive()&&!player.getAbilities().flying&&player.onGround(),"initial survival player on original ground");
            var base=BlockPos.containing(original);
            for(int x=-1;x<=12;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=3;y++){
                var pos=base.offset(x,y,z);if(y==-1||x==-1||x==12||Math.abs(z)==2)put(pos,Blocks.GLASS.defaultBlockState());
                else if(y<=2&&SceneFluidGeometry.canOccupy(level,pos))put(pos,Blocks.WATER.defaultBlockState());
            }
            player.teleportTo(level,original.x,original.y,original.z,Set.of(),-90,0,true);warmup=0;stage=1;return;
        }
        require(frame.health()==nativeHp&&player.getHealth()==hp,"Native and MC HP remain unchanged");
        if(stage==1){if(++warmup<40)return;require(player.isInWater()&&player.getAirSupply()>0,"actual player immersed with air");stage=2;return;}
        if(stage==2)return;
        if(stage==3){
            require(samples>=85&&swimming>=20&&underwater>=20&&moved>.5&&raised>.15,"actual sprint swimming and jump ascent: samples="+samples+" swim="+swimming+" underwater="+underwater+" distance="+moved+" rise="+raised);
            restore(server);stage=4;warmup=0;return;
        }
        if(stage==4){if(++warmup<20||!view.shapesCurrent()||player.position().distanceTo(original)>.05)return;
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-actual-active-swim.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"backend","actual LocalPlayer, routed keyboard, real Sifu bridge and ground","client_ticks",samples,"swimming_ticks",swimming,"underwater_ticks",underwater,"horizontal_m",moved,"rise_m",raised,"native_hp",nativeHp,"mc_hp",hp),out));
            finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[active-swim-test] PASS actual LocalPlayer swimming={} underwater={} distance={} rise={} restored with Native ACK",swimming,underwater,moved,raised);
        }
    }catch(Exception|AssertionError error){restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[active-swim-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}}
    void restore(MinecraftServer ignored){release=true;if(level!=null){for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}if(player!=null&&original!=null){player.teleportTo(level,original.x,original.y,original.z,Set.of(),yaw,pitch,true);player.setDeltaMovement(Vec3.ZERO);player.setAirSupply(air);}}
}
