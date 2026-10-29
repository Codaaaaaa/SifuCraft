package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockGeometry;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.RedstoneCatalog;
import dev.sifucraft.storage.AtomicFiles;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Installed candidate registry, real vanilla rasterization and per-state clear controls. */
final class CatalogModelCheck {
    private static CatalogModelCheck current;
    private final List<BlockState> cases=new ArrayList<>();private final List<Map<String,Object>> rows=new ArrayList<>();
    private volatile boolean prepared,captured,finished;private boolean started,empty=true,oldHud,oldFrozen;private int sample=-1,blocks;
    private long at,deadline;private BlockPos target;private BlockState original;private MinecraftServer server;
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("catalog model: "+why);}
    @SuppressWarnings({"rawtypes","unchecked"})private static BlockState value(BlockState state,Property property,Comparable value){return state.setValue(property,value);}
    CatalogModelCheck(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-catalog-model-check-"),"disposable save required");current=this;
        var states=new LinkedHashMap<String,BlockState>();var sorted=new ArrayList<Block>();BuiltInRegistries.BLOCK.forEach(sorted::add);sorted.sort(java.util.Comparator.comparing(b->BuiltInRegistries.BLOCK.getKey(b).toString()));
        for(var block:sorted){if(RedstoneCatalog.reasons(block).isEmpty())continue;blocks++;var state=block.defaultBlockState();states.put(BlockStateKey.encode(state),state);
            for(var property:state.getProperties()){
                if(!List.of("open","powered","lit","extended","facing").contains(property.getName()))continue;
                for(var option:property.getPossibleValues()){
                    if(property.getName().equals("facing")&&!option.equals(net.minecraft.core.Direction.EAST))continue;
                    var variant=value(state,property,option);states.put(BlockStateKey.encode(variant),variant);
                }
            }
        }cases.addAll(states.values());require(blocks==246,"installed registry fingerprint");
    }
    private boolean validEmpty(BlockState state){return state.is(Blocks.MOVING_PISTON);}
    private void prepare(Minecraft mc){
        prepared=false;captured=false;at=System.currentTimeMillis();mc.particleEngine.clearParticles();
        server.execute(()->{
            var level=server.overworld();if(original==null){original=level.getBlockState(target);require(!original.hasBlockEntity(),"no saved BE replaced");oldFrozen=server.tickRateManager().isFrozen();server.tickRateManager().setFrozen(true);}
            level.removeBlockEntity(target);
            var state=empty?Blocks.AIR.defaultBlockState():cases.get(sample);
            level.setBlock(target,state,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE|Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE);
            prepared=true;
        });
    }
    void tick(Minecraft mc,boolean ready){
        if(finished){mc.stop();return;}if(!ready||mc.player==null||mc.gui.screen()!=null)return;long now=System.currentTimeMillis();
        if(!started){require(BlockShapes.ENABLED,"shape channel required");started=true;server=mc.getSingleplayerServer();deadline=now+cases.size()*6000L+30000;oldHud=mc.gui.hud.isHidden();if(!oldHud)mc.gui.hud.toggle();target=BlockPos.containing(mc.player.position().add(mc.player.getLookAngle().scale(3))).above();prepare(mc);LoggerFactory.getLogger("sifucraft").info("[catalog-model-test] registry={} state samples={}",blocks,cases.size());return;}
        require(now<deadline,"bounded catalog deadline");if(!prepared||now-at<350||!SifuCraftClient.blockCollisionCurrent())return;
        if(!captured){require(now-at<6000,"missing original rendering state="+(empty?"clear":BlockStateKey.encode(cases.get(sample))));return;}
        if(empty){sample++;empty=false;}else empty=true;
        if(sample<cases.size()){prepare(mc);return;}
        server.execute(()->{
            try{restore(server);AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p8-catalog-models.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","candidate_blocks",blocks,"state_samples",cases.size(),"scope","MC original rasterization + paired depth + Native shape ACK; logical acceptance separate","rows",rows),out));LoggerFactory.getLogger("sifucraft").info("[catalog-model-test] PASS {} candidates / {} model-state captures with clear controls; restored",blocks,cases.size());finished=true;}catch(Exception e){throw new IllegalStateException(e);}
        });
        // Keep the render callback from scheduling completion twice while the server saves the report.
        prepared=false;
    }
    static void inspect(MemorySegment color,MemorySegment depth,int width,int height,WorldLayerCapture.Snapshot camera){
        var c=current;if(c==null||!c.started||c.finished||!c.prepared||c.captured||System.currentTimeMillis()-c.at<350||!SifuCraftClient.blockCollisionCurrent())return;
        var mc=Minecraft.getInstance();var expected=c.empty?Blocks.AIR.defaultBlockState():c.cases.get(c.sample);if(mc.level.getBlockState(c.target)!=expected)return;
        int visible=0,covered=0,near=0;var inverse=new org.joml.Matrix4f(camera.projection()).mul(camera.viewRotation()).invert();var center=Vec3.atCenterOf(c.target);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            long offset=((long)y*width+x)*4;if(Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE,offset+3))<=8)continue;visible++;
            float z=depth.get(ValueLayout.JAVA_FLOAT,offset);if(!Float.isFinite(z)||z<=0||z>1)continue;covered++;
            var p=inverse.transform(new org.joml.Vector4f(2f*(x+.5f)/width-1,2f*(y+.5f)/height-1,z,1));
            if(new Vec3(camera.x()+p.x/p.w,camera.y()+p.y/p.w,camera.z()+p.z/p.w).distanceToSqr(center)<4)near++;
        }
        if(c.empty){if(visible!=0)return;}
        else{
            boolean blank=c.validEmpty(expected);if(!blank&&visible<20)return;
            require(blank?visible==0:covered>=visible*.95&&near>=covered*.99,"paired depth/position "+BlockStateKey.encode(expected)+" "+near+"/"+covered+"/"+visible);
            var shape=BlockGeometry.at(mc.level,c.target,expected);c.rows.add(Map.of("state",BlockStateKey.encode(expected),"visible",visible,"valid_depth",covered,"near_block",near,"block_entity",expected.hasBlockEntity(),"legal_empty_payload",blank,"geometry",shape.toString()));
            LoggerFactory.getLogger("sifucraft").info("[catalog-model-test] {}/{} state={} visible={} depth={} near={}",c.sample+1,c.cases.size(),BlockStateKey.encode(expected),visible,covered,near);
        }c.captured=true;
    }
    void restore(MinecraftServer s){if(original==null)return;var level=s.overworld();level.removeBlockEntity(target);level.setBlock(target,original,2);s.tickRateManager().setFrozen(oldFrozen);original=null;var mc=Minecraft.getInstance();mc.execute(()->{if(mc.gui.hud.isHidden()!=oldHud)mc.gui.hud.toggle();});}
}
