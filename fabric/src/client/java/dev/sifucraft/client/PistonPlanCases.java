package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonContacts;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyProxy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Source-bound local proposals in the disposable world. No Native lease, sender or actor calls. */
final class PistonPlanCases {
    @FunctionalInterface interface Scenes { SifuCollision.Active make(BlockPos wall,Direction direction,String kind) throws IOException; }
    private static final long NATIVE=11,MC=22;
    private static final int WORLD=9;
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);refreshDimensions();}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston plan: "+why);}
    private static List<Double> values(Vec3 v){return List.of(v.x,v.y,v.z);}
    private static SifuCollision.Active scoped(SifuCollision.Active raw){return new SifuCollision.Active(raw.grid(),raw.placement(),raw.doors(),NATIVE,WORLD);}
    private static EnemyFrame.Entry entry(Vec3 feet,SifuCollision.Active scene,int spawn,float hp){
        var p=scene.placement();return new EnemyFrame.Entry(900007,spawn,new SifuFrame.Vec3((float)p.ueX(feet.x),(float)p.ueY(feet.z),(float)p.ueZ(feet.y+.92)),
            0,35,92,hp,60,Protocol.ENEMY_ALIVE,1,SifuFrame.Vec3.ZERO);
    }
    private static PistonMovePlans.View view(PistonMovePlans.Scope scope,EnemyFrame.Entry enemy,SifuCollision.Active scene,long time,long frame){
        return new PistonMovePlans.View(scope,new EnemyFrame(NATIVE,frame,WORLD,time,List.of(enemy)),scene);
    }
    private static Map<String,Object> run(ServerLevel level,BlockPos pos,Direction face,boolean extending,String geometry,String mode,Scenes scenes) throws IOException {
        var originals=new LinkedHashMap<BlockPos,BlockState>();
        var facing=extending?face:face.getOpposite();
        var moving=Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,facing);
        var piston=new PistonMovingBlockEntity(pos,moving,Blocks.STONE.defaultBlockState(),facing,extending,false);
        var empty=scoped(scenes.make(null,face,"empty"));var scene=empty;var wall=pos.relative(face);
        if(geometry.startsWith("native")){
            var solid=scoped(scenes.make(wall,face,geometry.equals("native-thin")?"thin":geometry.contains("slab")?"slab":"full"));
            scene=geometry.equals("native-door")?new SifuCollision.Active(empty.grid(),empty.placement(),solid.grid(),NATIVE,WORLD):solid;
        }
        var proxy=new EnemyProxy(EnemyEntities.ENEMY_PROXY,level);var reference=new Reference(level);
        var initial=pos.relative(face.getOpposite());double x=initial.getX()+.5,y=initial.getY()+.05,z=initial.getZ()+.5;
        switch(face){case EAST->x=initial.getX()+1.45;case WEST->x=initial.getX()-.45;case SOUTH->z=initial.getZ()+1.45;case NORTH->z=initial.getZ()-.45;default->throw new IllegalArgumentException("horizontal fixture");}
        if(geometry.equals("native-slab-clear"))y+=.55;
        var enemy=entry(new Vec3(x,y,z),scene,7,60);proxy.mirror(enemy,scene.placement());proxy.refreshDimensions();
        reference.setPos(proxy.position());reference.setBoundingBox(proxy.getBoundingBox());reference.setOnGround(false);
        var start=proxy.position();var scope=new PistonMovePlans.Scope(MC,NATIVE,WORLD,PistonMovePlans.saveKey(level),scene.placement());
        var clock=new AtomicLong(System.currentTimeMillis());var current=new AtomicReference<>(view(scope,enemy,scene,clock.get(),100));
        final var selectedScene=scene;var checked=new ArrayList<Map<String,Object>>();
        try{
            for(var at:List.of(pos,wall,wall.above(),pos.above(4))){var old=level.getBlockState(at);require(!old.hasBlockEntity(),"fixture replaces no BE");originals.put(at,old);}
            level.setBlock(pos,moving,2);level.setBlockEntity(piston);
            if(geometry.startsWith("mc-")){
                level.setBlock(wall,geometry.equals("mc-slab")?Blocks.STONE_SLAB.defaultBlockState():Blocks.STONE.defaultBlockState(),2);
                if(geometry.equals("mc-full"))level.setBlock(wall.above(),Blocks.STONE.defaultBlockState(),2);
            }
            require(level.addFreshEntity(proxy),"proxy registered");
            var planner=new PistonMovePlans(level,current::get,clock::get);
            var capture=new PistonContacts.Capture(level,planner,attempt->{
                require(attempt.status()==PistonMovePlans.Status.PLANNED,"fresh source proposal: "+geometry+"/"+mode+"/"+attempt.status());
                var plan=attempt.plan();var collision=plan.collision();var before=proxy.position();var box=proxy.getBoundingBox();
                require(planner.validate(plan)==PistonMovePlans.Status.PLANNED,"initial ticket valid");
                require(proxy.position().equals(before)&&proxy.getBoundingBox().equals(box),"planning never changes the proxy pose");
                var original=current.get();PistonMovePlans.Status expected=PistonMovePlans.Status.TAKEN;
                switch(mode){
                    case "stale"->{clock.addAndGet(201);expected=PistonMovePlans.Status.CONTEXT;}
                    case "future"->{current.set(view(scope,enemy,selectedScene,clock.get()+1,100));expected=PistonMovePlans.Status.CONTEXT;}
                    case "disconnect"->{current.set(null);expected=PistonMovePlans.Status.CONTEXT;}
                    case "session"->{current.set(view(new PistonMovePlans.Scope(MC+1,NATIVE,WORLD,scope.save(),scope.placement()),enemy,selectedScene,clock.get(),100));expected=PistonMovePlans.Status.CONTEXT;}
                    case "save"->{current.set(view(new PistonMovePlans.Scope(MC,NATIVE,WORLD,scope.save()+1,scope.placement()),enemy,selectedScene,clock.get(),100));expected=PistonMovePlans.Status.CONTEXT;}
                    case "world"->{current.set(view(new PistonMovePlans.Scope(MC,NATIVE,WORLD+1,scope.save(),scope.placement()),enemy,selectedScene,clock.get(),100));expected=PistonMovePlans.Status.CONTEXT;}
                    case "geometry"->{current.set(view(scope,enemy,new SifuCollision.Active(selectedScene.grid(),selectedScene.placement(),selectedScene.doors(),NATIVE,WORLD),clock.get(),100));expected=PistonMovePlans.Status.CHANGED;}
                    case "health"->{current.set(view(scope,entry(proxy.position(),selectedScene,7,59),selectedScene,clock.get(),100));expected=PistonMovePlans.Status.TARGET;}
                    case "spawn"->{current.set(view(scope,entry(proxy.position(),selectedScene,8,60),selectedScene,clock.get(),100));expected=PistonMovePlans.Status.TARGET;}
                    case "frame-back"->{current.set(view(scope,enemy,selectedScene,clock.get(),99));expected=PistonMovePlans.Status.TARGET;}
                    case "frame-forward"->current.set(view(scope,enemy,selectedScene,clock.get(),101));
                    case "index"->{level.setBlock(pos.above(4),Blocks.STONE.defaultBlockState(),2);expected=PistonMovePlans.Status.CHANGED;}
                    case "source"->{level.setBlockEntity(new PistonMovingBlockEntity(pos,moving,Blocks.STONE.defaultBlockState(),facing,extending,false));expected=PistonMovePlans.Status.SOURCE;}
                    case "target-pose"->{proxy.setPos(proxy.position().add(.1,0,0));expected=PistonMovePlans.Status.TARGET;}
                    case "cancel"->{require(planner.cancel(plan)==PistonMovePlans.Status.CANCELLED,"cancel before dispatch");expected=PistonMovePlans.Status.USED;}
                    case "foreign"->{var other=new PistonMovePlans(level,current::get,clock::get);require(other.take(plan)==PistonMovePlans.Status.USED,"another planner cannot consume token");}
                    default->{/* collision baseline */}
                }
                var actual=planner.take(plan);require(actual==expected,mode+" dispatch="+actual+" expected="+expected);
                current.set(original);clock.set(original.enemies().receivedMs());
                require(planner.take(plan)==PistonMovePlans.Status.USED,"a used or invalidated token never resumes after recovery");
                if(mode.equals("target-pose"))proxy.mirror(enemy,selectedScene.placement());
                if(actual==PistonMovePlans.Status.TAKEN){
                    var old=reference.position();
                    SifuCollision.withQueryView(selectedScene,()->{reference.move(MoverType.PISTON,collision.requested());return null;});
                    var moved=reference.position().subtract(old);
                    require(moved.distanceToSqr(collision.collided())<1e-12,"plan matches ordinary no-step motion in original piston call stack");
                    if(mode.equals("take")){
                        boolean clipped=collision.limited().distanceToSqr(collision.collided())>1e-10;
                        require(clipped==(!geometry.equals("clear")&&!geometry.equals("native-slab-clear")),"expected obstacle result: "+geometry);
                    }
                }
                checked.add(Map.of("status",actual.name(),"limited",values(collision.limited()),"collided",values(collision.collided()),
                    "source_action",plan.source().action(),"ordinal",plan.ordinal(),"entity_key",List.of(plan.target().id(),plan.target().spawn())));
            });
            capture.run(()->PistonMovingBlockEntity.tick(level,pos,moving,piston));
            require(!checked.isEmpty()&&capture.contacts().size()==checked.size(),"actual vanilla source/move captured");
            return Map.of("case","piston-plan","face",face.getName(),"extending",extending,"geometry",geometry,"mode",mode,"attempts",checked,"proxy_actual",values(proxy.position().subtract(start)));
        }finally{proxy.discard();reference.discard();for(var at:originals.keySet())level.removeBlockEntity(at);for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);}
    }
    static List<Map<String,Object>> run(ServerLevel level,BlockPos base,Scenes scenes) throws IOException {
        base=new BlockPos(Math.floorDiv(base.getX(),16)*16+8,base.getY(),Math.floorDiv(base.getZ(),16)*16+8);
        var rows=new ArrayList<Map<String,Object>>();
        for(var face:List.of(Direction.EAST,Direction.WEST,Direction.NORTH,Direction.SOUTH))
            for(boolean extending:new boolean[]{false,true})for(var geometry:List.of("clear","mc-full","mc-slab","native-full","native-slab","native-slab-clear","native-thin","native-door"))
                rows.add(run(level,base,face,extending,geometry,"take",scenes));
        for(var mode:List.of("stale","future","disconnect","session","save","world","geometry","health","spawn","frame-back","frame-forward","index","source","target-pose","cancel","foreign"))
            rows.add(run(level,base,Direction.EAST,true,"clear",mode,scenes));
        var a=scoped(scenes.make(null,Direction.EAST,"empty"));var b=scoped(scenes.make(base,Direction.EAST,"full"));var live=SifuCollision.active();
        SifuCollision.withQueryView(a,()->{
            try{SifuCollision.withQueryView(b,()->{throw new IllegalStateException("fixture");});}catch(IllegalStateException expected){/* nested view must restore */}
            require(SifuCollision.queryView()==a,"nested/exception movement view restores parent");
            var seen=new AtomicReference<SifuCollision.Active>();var thread=new Thread(()->seen.set(SifuCollision.queryView()));thread.start();
            try{thread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            require(!thread.isAlive()&&seen.get()==live,"other thread sees the live geometry, never a server proposal override");return null;
        });
        require(SifuCollision.queryView()==live,"outer scope restores original collision view");
        rows.add(Map.of("case","piston-plan-query-scope","nested_exception",true,"thread_isolated",true));
        return rows;
    }
}
