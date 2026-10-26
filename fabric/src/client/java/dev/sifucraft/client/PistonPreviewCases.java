package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonPreview;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.mixin.PistonAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Real installed private contact calls, then independent ordinary body movement, in a disposable save. */
final class PistonPreviewCases {
    private static final class Reference extends Cow {
        Reference(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);setSilent(true);refreshDimensions();}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(.7f,1.84f);}
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("piston preview: "+why);}
    private static List<Double> values(Vec3 v){return List.of(v.x,v.y,v.z);}
    private static Map<String,Object> run(ServerLevel level,BlockPos pos,Direction face,boolean extending,BlockState moved,boolean proxy,boolean carried,SifuCollision.Active scene){
        var original=level.getBlockState(pos);require(!original.hasBlockEntity(),"fixture original has no BE");
        var moving=Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,face);
        var piston=new PistonMovingBlockEntity(pos,moving,moved,face,extending,false);var direction=piston.getMovementDirection();
        var initial=pos.relative(extending?face.getOpposite():face);
        double x=initial.getX()+.5,y=initial.getY()+.05,z=initial.getZ()+.5;
        if(carried)y=initial.getY()+1.01;
        else switch(direction){case EAST->x=initial.getX()+1.45;case WEST->x=initial.getX()-.45;
            case SOUTH->z=initial.getZ()+1.45;case NORTH->z=initial.getZ()-.45;case UP->y=initial.getY()+1.1;case DOWN->y=initial.getY()-.1-1.84;}
        Mob body=proxy?new EnemyProxy(EnemyEntities.ENEMY_PROXY,level):new Reference(level);Reference reference=null;
        if(body instanceof EnemyProxy p){
            var placement=scene.placement();p.mirror(new EnemyFrame.Entry(900044,7,new SifuFrame.Vec3((float)placement.ueX(x),(float)placement.ueY(z),(float)placement.ueZ(y+.92)),
                0,35,92,60,60,Protocol.ENEMY_ALIVE,1,SifuFrame.Vec3.ZERO),placement);
        }else body.setPos(x,y,z);
        body.refreshDimensions();body.setOnGround(carried);body.setDeltaMovement(Vec3.ZERO);
        try{
            level.setBlock(pos,moving,2);level.setBlockEntity(piston);require(level.addFreshEntity(body),"body registered");
            var before=body.position();var box=body.getBoundingBox();var velocity=body.getDeltaMovement();float health=body.getHealth();boolean ground=body.onGround();
            var index=WorldBlocks.index();long revision=index.revision();var noClip=PistonAccess.sifucraft$noClip().get();
            var preview=PistonPreview.run(level,pos,piston,.5f,scene,Map.of());
            require(body.position().equals(before)&&body.getBoundingBox().equals(box)&&body.getDeltaMovement().equals(velocity)&&body.getHealth()==health&&body.onGround()==ground,"preview leaves every real body field intact");
            require(piston.getProgress(0)==0&&piston.getProgress(1)==0&&level.getBlockEntity(pos)==piston&&level.getBlockState(pos)==moving&&index.revision()==revision,"preview leaves BE/chunk/derived index intact");
            require(PistonAccess.sifucraft$noClip().get()==noClip&&!PistonPreview.active(),"thread scopes and vanilla NOCLIP restored");
            var result=preview.bodies().stream().filter(b->b.entity().equals(body.getUUID())).findFirst().orElseThrow();
            require(preview.moves().stream().anyMatch(m->m.entity().equals(body.getUUID())),"actual private contact called shadow move: "+direction+" proxy="+proxy+" carried="+carried);
            var actual=body;
            if(proxy){body.discard();reference=new Reference(level);reference.setPos(before);reference.setOnGround(carried);reference.setDeltaMovement(velocity);require(level.addFreshEntity(reference),"independent physical reference registered");actual=reference;}
            PistonMovingBlockEntity.tick(level,pos,moving,piston);
            require(actual.position().distanceToSqr(result.after())<1e-12,"shadow position matches independent installed Entity.move: "+direction+" proxy="+proxy+" got="+result.after()+" actual="+actual.position());
            require(actual.getDeltaMovement().distanceToSqr(result.velocity())<1e-12,"shadow slime velocity matches installed body");
            return Map.of("case","contact-preview","face",face.getName(),"extending",extending,"state",moved.toString(),"proxy",proxy,"carried",carried,
                "moves",preview.moves().size(),"actual",values(actual.position().subtract(before)),"velocity",values(result.velocity()));
        }finally{body.discard();if(reference!=null)reference.discard();level.removeBlockEntity(pos);level.setBlock(pos,original,2);}
    }
    static List<Map<String,Object>> run(ServerLevel level,BlockPos base,SifuCollision.Active scene){
        var rows=new ArrayList<Map<String,Object>>();
        for(var face:Direction.values())for(boolean extending:new boolean[]{false,true})
            for(var moved:List.of(Blocks.STONE.defaultBlockState(),Blocks.SLIME_BLOCK.defaultBlockState(),Blocks.HONEY_BLOCK.defaultBlockState()))
                for(boolean proxy:new boolean[]{false,true})rows.add(run(level,base,face,extending,moved,proxy,false,scene));
        for(var face:List.of(Direction.EAST,Direction.WEST,Direction.NORTH,Direction.SOUTH))for(boolean proxy:new boolean[]{false,true})
            rows.add(run(level,base,face,true,Blocks.HONEY_BLOCK.defaultBlockState(),proxy,true,scene));
        return rows;
    }
}
