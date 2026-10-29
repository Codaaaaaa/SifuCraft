package dev.sifucraft.client;

import dev.sifucraft.combat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Blocks;

/** Installed isInWall is the geometry oracle; disconnected proxies grant no damage. */
final class SuffocationCases {
    static List<Map<String,Object>> run(ServerLevel level){
        var reference=new Cow(EntityTypes.COW,level);
        var proxy=new EnemyProxy(EnemyEntities.ENEMY_PROXY,level);
        var records=new ArrayList<Map<String,Object>>();var originals=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        try{
            for(var block:List.of(Blocks.AIR,Blocks.STONE,Blocks.GLASS,Blocks.OAK_SLAB,Blocks.OAK_TRAPDOOR,Blocks.OAK_FENCE,Blocks.WATER)){
                reference.setPos(24.5,210,8.5);var pos=BlockPos.containing(reference.getEyePosition());
                originals.putIfAbsent(pos,level.getBlockState(pos));level.setBlockAndUpdate(pos,block.defaultBlockState());
                for(double dx:new double[]{0,.49,.8,1.1}){
                    reference.setPos(24.5+dx,210,8.5);
                    boolean vanilla=reference.isInWall(),proof=SuffocationContacts.block(reference)!=null;
                    if(vanilla!=proof)throw new AssertionError("installed suffocation mismatch "+block+" offset="+dx);
                    proxy.setPos(reference.position());
                    if(proxy.isInWall()||!proxy.noPhysics||dev.sifucraft.combat.WorldEffects.canCapture(proxy,proxy.damageSources().inWall()))throw new AssertionError("unbound body cannot acquire suffocation authority");
                    records.add(Map.of("kind","suffocation_geometry","block",block.toString(),"offset",dx,"original_in_wall",vanilla,"disconnected_proxy_refused",true));
                }
            }
        }finally{for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),18);reference.discard();proxy.discard();}
        if(records.stream().noneMatch(r->Boolean.TRUE.equals(r.get("original_in_wall"))))throw new AssertionError("positive stone control required");
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[isolated-systems-test] suffocation PASS: {} installed eye-query cases and unbound proxy negatives",records.size());
        return records;
    }
}
