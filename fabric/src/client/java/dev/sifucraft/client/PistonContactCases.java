package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonContacts;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyProxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Disposable, synchronous vanilla BE calls. Never submits a Native motion request. */
final class PistonContactCases {
    private record Run(Vec3 actual,Vec3 velocity,float health,float progress,List<PistonContacts.Contact> contacts){}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston contacts: "+why);}
    private static Vec3 vec(Direction direction,double distance){return new Vec3(direction.getStepX()*distance,direction.getStepY()*distance,direction.getStepZ()*distance);}
    private static boolean near(Vec3 a,Vec3 b){return a.distanceToSqr(b)<1e-10;}
    private static List<Double> values(Vec3 v){return List.of(v.x,v.y,v.z);}

    private static Run run(ServerLevel level,BlockPos pos,Direction face,boolean extending,BlockState moved,
                           int kind,boolean recording,int ticks,boolean carried){
        var original=level.getBlockState(pos);require(!original.hasBlockEntity(),"original fixture has no BE");
        var moving=Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,face);
        var piston=new PistonMovingBlockEntity(pos,moving,moved,face,extending,false);
        var movement=piston.getMovementDirection();
        var initial=pos.relative(extending?face.getOpposite():face);
        double x=initial.getX()+.5,y=initial.getY()+.05,z=initial.getZ()+.5;
        if(carried)y=initial.getY()+1.01;
        else switch(movement){
            case EAST -> x=initial.getX()+1.45;
            case WEST -> x=initial.getX()-.45;
            case SOUTH -> z=initial.getZ()+1.45;
            case NORTH -> z=initial.getZ()-.45;
            case UP -> y=initial.getY()+1.1;
            case DOWN -> y=initial.getY()-.1-1.84;
        }
        Mob body=kind==2?new EnemyProxy(EnemyEntities.ENEMY_PROXY,level):EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);
        require(body!=null,"body created");
        if(body instanceof EnemyProxy proxy)proxy.mirror(new EnemyFrame.Entry(900001,7,
            new SifuFrame.Vec3((float)(x*100),(float)(z*100),(float)((y+.92)*100)),0,35,92,60,60,
            Protocol.ENEMY_ALIVE,1,SifuFrame.Vec3.ZERO),new MapPlacement("piston-contact",0,0,0));
        body.setNoAi(true);body.setNoGravity(true);body.setSilent(true);body.noPhysics=kind!=0;
        body.setPos(x,y,z);body.setBoundingBox(new AABB(x-.35,y,z-.35,x+.35,y+1.84,z+.35));
        body.setOnGround(carried);body.setDeltaMovement(Vec3.ZERO);
        var before=body.position();float health=body.getHealth();
        var capture=new PistonContacts.Capture(level);
        try{
            level.setBlock(pos,moving,2);level.setBlockEntity(piston);require(level.addFreshEntity(body),"body registered");
            require(level.getEntities((net.minecraft.world.entity.Entity)null,body.getBoundingBox().inflate(2)).contains(body),"fixture entity is queryable in a ticking chunk");
            Runnable tick=()->{for(int i=0;i<ticks;i++)PistonMovingBlockEntity.tick(level,pos,moving,piston);};
            if(recording)capture.run(tick);else tick.run();
            require(body.getHealth()==health,"observation preserves health");
            var contacts=capture.contacts();
            if(recording){
                require(!contacts.isEmpty(),"actual vanilla piston called move: "+face+" extend="+extending+" state="+moved+" body="+kind+" position="+before+" delta="+body.position().subtract(before));
                long action=contacts.getFirst().source().action();
                for(var c:contacts){
                    var s=c.source();require(s.action()==action&&s.pos().equals(pos)&&s.movement()==movement&&s.extending()==extending,"source action/direction stable");
                    require(s.tick()==level.getGameTime()&&s.progress()<s.nextProgress()&&s.nextProgress()<=1,"actual source tick/progress");
                    require((c.enemy()!=null)==(kind==2),"only proxy contacts have Native activation key");
                    if(c.enemy()!=null)require(c.enemy().id()==900001&&c.enemy().spawn()==7,"exact activation preserved");
                    require(s.phase()==(carried?PistonContacts.Phase.CARRIED:PistonContacts.Phase.COLLIDED),"collision and honey carrying distinguished");
                }
                // A failed observer must not leave source or capture authority on the server thread.
                try{capture.run(()->PistonContacts.source(level,pos,1,piston,PistonContacts.Phase.COLLIDED,()->{throw new IllegalArgumentException("fixture");}));}
                catch(IllegalArgumentException expected){/* source and capture finally restore */}
                int count=capture.contacts().size();
                PistonContacts.move(body,MoverType.PISTON,Vec3.ZERO);
                require(capture.contacts().size()==count&&!PistonContacts.observing(),"exception leaves no capture/source outside scope");
            }
            return new Run(body.position().subtract(before),body.getDeltaMovement(),body.getHealth(),piston.getProgress(1),contacts);
        }finally{body.discard();level.removeBlockEntity(pos);level.setBlock(pos,original,2);}
    }

    static List<Map<String,Object>> run(ServerLevel level,BlockPos base){
        var rows=new ArrayList<Map<String,Object>>();
        for(var face:Direction.values())for(boolean extending:new boolean[]{false,true})
            for(var moved:List.of(Blocks.STONE.defaultBlockState(),Blocks.SLIME_BLOCK.defaultBlockState(),Blocks.HONEY_BLOCK.defaultBlockState()))
                for(int kind=0;kind<3;kind++){
                    var plain=run(level,base,face,extending,moved,kind,false,1,false);
                    var captured=run(level,base,face,extending,moved,kind,true,1,false);
                    require(near(plain.actual,captured.actual)&&near(plain.velocity,captured.velocity)&&plain.health==captured.health&&plain.progress==captured.progress,"capture preserves vanilla trajectory");
                    require(captured.actual.length()>0,"actual collision moved body");
                    var row=new LinkedHashMap<String,Object>();row.put("case","contact");row.put("face",face.getName());row.put("extending",extending);
                    row.put("state",moved.toString());row.put("body",kind);row.put("actual",values(captured.actual));row.put("velocity",values(captured.velocity));
                    row.put("contacts",captured.contacts.size());row.put("requested",values(captured.contacts.getFirst().requested()));rows.add(row);
                }
        for(var face:List.of(Direction.EAST,Direction.WEST,Direction.NORTH,Direction.SOUTH))for(int kind=0;kind<3;kind++){
            var moved=Blocks.HONEY_BLOCK.defaultBlockState();
            var plain=run(level,base,face,true,moved,kind,false,1,true);var captured=run(level,base,face,true,moved,kind,true,1,true);
            require(near(plain.actual,captured.actual)&&near(captured.actual,vec(face,.5)),"honey carrying matches vanilla");
            rows.add(Map.of("case","contact-honey-carry","face",face.getName(),"body",kind,"actual",values(captured.actual),"contacts",captured.contacts.size()));
        }
        var ordinary=run(level,base,Direction.EAST,true,Blocks.STONE.defaultBlockState(),0,true,2,false);
        var proxy=run(level,base,Direction.EAST,true,Blocks.STONE.defaultBlockState(),2,true,2,false);
        require(Math.abs(ordinary.actual.x-.51)<1e-6&&proxy.actual.x>.51,"noPhysics bypasses vanilla same-tick piston cap");
        require(proxy.contacts.size()==2&&ordinary.contacts.size()==2,"both vanilla calls captured separately");
        rows.add(Map.of("case","contact-same-tick-cap","ordinary",values(ordinary.actual),"proxy",values(proxy.actual),
            "requested",proxy.contacts.stream().map(c->values(c.requested())).toList(),"contacts",2));
        return rows;
    }
}
