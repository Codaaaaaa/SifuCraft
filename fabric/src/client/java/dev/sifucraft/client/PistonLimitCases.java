package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMotionLimit;
import dev.sifucraft.blocks.PistonContacts;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Differential tests against the installed protected vanilla method, only in the disposable test world. */
final class PistonLimitCases {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston limit: "+why);}
    private static Vec3 vec(Direction d,double n){return new Vec3(d.getStepX()*n,d.getStepY()*n,d.getStepZ()*n);}
    private static List<Double> values(Vec3 v){return List.of(v.x,v.y,v.z);}
    static Vec3 replay(ServerLevel level,List<PistonContacts.Contact> contacts) throws ReflectiveOperationException {
        var method=Entity.class.getDeclaredMethod("limitPistonMovement",Vec3.class);method.setAccessible(true);
        var reference=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);
        require(reference!=null,"source replay reference created");
        var state=PistonMotionLimit.State.empty();var total=Vec3.ZERO;
        try{
            for(var contact:contacts){
                require(contact.source().tick()==level.getGameTime(),"replay source uses this real server tick");
                var expected=(Vec3)method.invoke(reference,contact.requested());
                var step=PistonMotionLimit.plan(state,contact.source().tick(),contact.requested());state=step.state();
                require(step.limited().distanceToSqr(expected)<1e-24,"captured source request matches installed limiter");
                total=total.add(step.limited());
            }
            return total;
        }finally{reference.discard();}
    }
    private static Map<String,Object> run(ServerLevel level,BlockPos pos,Method vanilla,String name,List<Vec3> requests,Direction wall) throws ReflectiveOperationException {
        var body=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);
        var reference=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);
        require(body!=null&&reference!=null,"reference bodies created");
        body.setNoAi(true);body.setNoGravity(true);body.setSilent(true);body.noPhysics=false;
        double x=pos.getX()+.5,y=pos.getY()+.05,z=pos.getZ()+.5;
        body.setPos(x,y,z); // retain the cow's real dimensions across Entity.setPos calls
        var originals=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var state=PistonMotionLimit.State.empty();var total=Vec3.ZERO;var actual=Vec3.ZERO;int blocked=0;
        float hp=body.getHealth();var steps=new ArrayList<Map<String,Object>>();
        try {
            if(wall!=null)for(int dy=0;dy<2;dy++){
                var at=pos.relative(wall).above(dy);var old=level.getBlockState(at);
                require(!old.hasBlockEntity(),"wall replaces no BE");originals.put(at,old);level.setBlock(at,Blocks.STONE.defaultBlockState(),2);
                require(level.getBlockState(at).is(Blocks.STONE),"actual wall state installed");
            }
            require(level.addFreshEntity(body),"ordinary body registered");
            for(var request:requests){
                var expected=(Vec3)vanilla.invoke(reference,request);
                var planned=PistonMotionLimit.plan(state,level.getGameTime(),request);state=planned.state();
                require(planned.limited().distanceToSqr(expected)<1e-24,name+" matches installed limiter for "+request);
                var before=body.position();body.move(MoverType.PISTON,request);var moved=body.position().subtract(before);
                if(wall==null)require(moved.distanceToSqr(expected)<1e-16,name+" free ordinary movement matches plan");
                else if(moved.distanceToSqr(expected)>1e-12)blocked++;
                require(body.getHealth()==hp,"no damage from planning or motion fixture");
                total=total.add(planned.limited());actual=actual.add(moved);
                steps.add(Map.of("requested",values(request),"limited",values(planned.limited()),"actual",values(moved)));
            }
            if(wall!=null){
                require(blocked>=2,name+" wall clips two actual moves: "+steps);
                require(((List<?>)steps.get(1).get("limited")).stream().mapToDouble(v->Math.abs((Double)v)).sum()<.101,
                    "blocked first move still spends the piston budget before collision");
            }
            return Map.of("case","piston-budget","name",name,"steps",steps,"total",values(total),"actual",values(actual),"blocked",blocked);
        }finally{body.discard();reference.discard();for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);}
    }
    static List<Map<String,Object>> run(ServerLevel level,BlockPos base) throws ReflectiveOperationException {
        // Keep walls and motion inside the same ticking chunk, including at a player chunk edge.
        base=new BlockPos(Math.floorDiv(base.getX(),16)*16+8,base.getY(),Math.floorDiv(base.getZ(),16)*16+8);
        var method=Entity.class.getDeclaredMethod("limitPistonMovement",Vec3.class);method.setAccessible(true);
        var rows=new ArrayList<Map<String,Object>>();
        for(var face:Direction.values()){
            rows.add(run(level,base,method,"signed-"+face.getName(),List.of(vec(face,.41),vec(face,.5),vec(face,.1),vec(face,-2),vec(face,.5)),null));
            rows.add(run(level,base,method,"tiny-"+face.getName(),List.of(vec(face,.0001),vec(face,.509999),vec(face,.1),Vec3.ZERO,vec(face,-.2)),null));
        }
        for(var face:List.of(Direction.EAST,Direction.WEST,Direction.NORTH,Direction.SOUTH))
            rows.add(run(level,base,method,"wall-"+face.getName(),List.of(vec(face,.41),vec(face,.5),vec(face,-.9),vec(face,.5)),face));
        rows.add(run(level,base,method,"independent-axes",List.of(new Vec3(.4,0,0),new Vec3(0,.4,0),new Vec3(0,0,-.4),new Vec3(.4,0,0)),null));
        var random=new Random(0x508ca9);
        for(int i=0;i<16;i++){
            var requests=new ArrayList<Vec3>();
            for(int j=0;j<32;j++)requests.add(vec(Direction.values()[random.nextInt(6)],random.nextDouble()*2));
            rows.add(run(level,base,method,"seeded-"+i,requests,null));
        }
        return rows;
    }
}
