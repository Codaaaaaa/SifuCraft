package dev.sifucraft.combat;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Differential installed Entity effects; unregistered equal bodies, no speculative Native moves. */
public final class EnvironmentPathCheck {
    private static final class Body extends Cow {
        Body(ServerLevel level){super(EntityTypes.COW,level);refreshDimensions();setSilent(true);getRandom().setSeed(813);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private ServerLevel level;private volatile boolean finished;private boolean ran;
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private static final BlockPos BASE=new BlockPos(32,64,0);
    public EnvironmentPathCheck(String save){require(save.startsWith("sifucraft-environment-path-check-"),"disposable save required");}
    private static void require(boolean value,String why){if(!value)throw new AssertionError("environment path: "+why);}
    public void clientTick(Minecraft mc){if(finished)mc.stop();}
    private void set(BlockPos pos,BlockState state){originals.putIfAbsent(pos.immutable(),level.getBlockState(pos));level.setBlock(pos,state,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE|Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE);}
    private void cell(int x,int y,BlockState state){set(BASE.offset(x,y,0),state);}
    private Map<String,Object> run(int pattern,int fire,boolean split,boolean reverse){
        for(int x=0;x<=5;x++)for(int y=1;y<=5;y++)cell(x,y,Blocks.AIR.defaultBlockState());
        var water=Blocks.WATER.defaultBlockState();var flame=Blocks.FIRE.defaultBlockState();var lava=Blocks.LAVA.defaultBlockState();
        switch(pattern){
            case 1->cell(2,1,flame);
            case 2->cell(2,1,water);
            case 3->cell(2,1,lava);
            case 4->{cell(1,1,flame);cell(3,1,water);}
            case 5->{cell(1,1,water);cell(3,1,flame);}
            case 6->{cell(1,1,lava);cell(3,1,water);}
            case 7->{cell(1,1,water);cell(3,1,lava);}
            case 8->{cell(1,1,flame);cell(2,1,flame);cell(3,1,flame);}
            case 9->cell(2,1,Blocks.SOUL_FIRE.defaultBlockState());
            case 10->cell(2,4,water);
            case 11->cell(2,1,water.setValue(BlockStateProperties.LEVEL,7));
            default->{}
        }
        var from=new Vec3(BASE.getX()+.5,BASE.getY()+1,BASE.getZ()+.5);var to=from.add(4,0,0);
        if(pattern==10){from=new Vec3(BASE.getX()+2.5,BASE.getY()+5,BASE.getZ()+.5);to=from.add(0,-4,0);}
        if(pattern==11){from=from.add(0,.5,0);to=to.add(0,.5,0);}
        if(reverse){var swap=from;from=to;to=swap;}
        var reference=new Body(level);var candidate=new Body(level);
        try{
            reference.setPos(to);candidate.setPos(to);reference.setRemainingFireTicks(fire);candidate.setRemainingFireTicks(fire);
            float hp=candidate.getHealth();
            reference.applyEffectsFromBlocks(from,to);
            var movements=new ArrayList<BlockEnvironment.Movement>();int parts=split?40:1;
            var delta=to.subtract(from);for(int i=0;i<parts;i++)movements.add(new BlockEnvironment.Movement(from.add(delta.scale((double)i/parts)),from.add(delta.scale((double)(i+1)/parts))));
            var contacts=new ArrayList<BlockEnvironment.Contact>();BlockEnvironment.apply(candidate,movements,contacts::add);
            String why="pattern="+pattern+" fire="+fire+" split="+split+" reverse="+reverse;
            require(candidate.getHealth()==reference.getHealth(),why+" HP candidate="+candidate.getHealth()+" reference="+reference.getHealth());
            require(candidate.getRemainingFireTicks()==reference.getRemainingFireTicks(),why+" fire candidate="+candidate.getRemainingFireTicks()+" reference="+reference.getRemainingFireTicks());
            if(pattern==1||pattern==3||pattern>=4&&pattern<=9)require(candidate.getHealth()<hp,"crossed medium actually hurt the reference and candidate: "+why);
            if((pattern==2||pattern==10)&&fire>0)require(!candidate.isOnFire(),"crossed water extinguishes even with a dry final body: "+why);
            if(pattern==0||pattern==11)require(candidate.getHealth()==hp&&candidate.getRemainingFireTicks()==reference.getRemainingFireTicks(),"dry/low liquid negative: "+why);
            require(candidate.position().equals(to)&&!BlockEnvironment.active(candidate),"no pose change or leaked context: "+why);
            return Map.of("pattern",pattern,"initial_fire",fire,"split",split,"reverse",reverse,"hp",candidate.getHealth(),"fire",candidate.getRemainingFireTicks(),"contacts",contacts.size());
        }finally{reference.discard();candidate.discard();}
    }
    public void tick(MinecraftServer server){
        if(finished)return;var view=WorldMotionWorld.current();if(view==null||!view.shapesCurrent()||!view.placement().map().equals("MotionFixture"))return;
        try{
            if(!ran){ran=true;level=server.overworld();for(int pattern=0;pattern<12;pattern++)for(int fire:new int[]{-20,0,60,300})for(boolean split:new boolean[]{false,true})for(boolean reverse:new boolean[]{false,true})rows.add(run(pattern,fire,split,reverse));return;}
            long now=dev.sifucraft.bridge.WorldWriter.clockMillis();
            if(view.nativeStatus()==null||now-view.nativeStatus().atMs()>100||System.currentTimeMillis()-view.enemies().receivedMs()>100)return;
            var target=EnemyMirrors.hitAttacker(101,1);require(target!=null,"unchanged live endpoint for receipt guards");
            var synthetic=new BlockEnvironment.Movement(new Vec3(32.5,65,.5),new Vec3(36.5,65,.5));
            cell(2,1,Blocks.WATER.defaultBlockState());target.setRemainingFireTicks(60);
            WorldEffects.receipted(target,view.scope(),synthetic,now);WorldEffects.touch(target);
            require(target.getRemainingFireTicks()==0,"explicitly synthetic fresh-path positive control reaches the live proxy despite its dry actual pose/noPhysics");
            cell(2,1,Blocks.LAVA.defaultBlockState());long dropped=WorldEffects.stats().pathDropped(),sent=WorldEffects.stats().sent();
            WorldEffects.receipted(target,view.scope(),synthetic,dev.sifucraft.bridge.WorldWriter.clockMillis()-201);WorldEffects.touch(target);
            var s=view.scope();var wrong=new dev.sifucraft.bridge.WorldMotionRequests.Scope(s.mc(),s.nativeSession(),s.world(),s.save()+1,s.map());
            WorldEffects.receipted(target,wrong,synthetic,dev.sifucraft.bridge.WorldWriter.clockMillis());WorldEffects.touch(target);
            require(WorldEffects.stats().pathDropped()==dropped+2&&WorldEffects.stats().sent()==sent,"synthetic expired/wrong-save negative controls cannot issue damage");
            require(rows.size()==192,"all cases run");restore(server);
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-environment-path.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","cases",rows,"synthetic_receipt_guards","fresh water clears fire; expired/wrong-save lava cannot damage", "kind","installed original Cow environment callbacks; receipt guard inputs synthetic, not Native integration"),out));
            LoggerFactory.getLogger("sifucraft").info("[environment-path-test] PASS {} original callback HP/fire comparisons, split path/order/no-pose-change/context restored",rows.size());finished=true;
        }catch(Exception|AssertionError error){restore(server);finished=true;LoggerFactory.getLogger("sifucraft").error("[environment-path-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    public void restore(MinecraftServer ignored){if(level!=null)for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),2);originals.clear();}
}
