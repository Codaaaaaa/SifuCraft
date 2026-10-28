package dev.sifucraft.client;

import dev.sifucraft.combat.BoatCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Original installed boats: contact boarding, capacity, seat transform and cargo persistence. */
final class BoatBaselineCheck {
    private BoatBaselineCheck() {}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("boat baseline: "+why);}
    static List<Map<String,Object>> run(ServerLevel level) {
        var reports=new ArrayList<Map<String,Object>>();
        for(int x=22;x<=26;x++)for(int z=6;z<=10;z++)level.setBlock(new BlockPos(x,200,z),Blocks.OBSIDIAN.defaultBlockState(),2);
        for(var entry:BoatCatalog.installed()) {
            var entity=entry.type().create(level,EntitySpawnReason.COMMAND);
            require(entity instanceof AbstractBoat,"registry candidate is a real boat: "+entry.id());
            var boat=(AbstractBoat)entity;
            boat.setPos(24.5,201,8.5);boat.setYRot(35);boat.setDeltaMovement(Vec3.ZERO);
            require(level.addFreshEntity(boat),"register original boat "+entry.id());
            var cows=new ArrayList<Cow>();
            for(int i=0;i<3;i++) {
                var cow=new Cow(EntityTypes.COW,level);cow.setNoAi(true);cow.setPos(24.4+i*.1,201,8.5);
                require(level.addFreshEntity(cow),"nearby vanilla mob");cows.add(cow);
            }
            level.tickNonPassenger(boat);
            int capacity=boat instanceof Container?1:2;
            require(boat.getPassengers().size()==capacity,"vanilla contact boarding and capacity "+entry.id()+" got="+boat.getPassengers().size());
            for(var passenger:List.copyOf(boat.getPassengers()))boat.positionRider(passenger);
            var seats=boat.getPassengers().stream().map(e->List.of(e.getX()-boat.getX(),e.getY()-boat.getY(),e.getZ()-boat.getZ())).toList();
            require(seats.stream().allMatch(s->Math.abs(s.get(0))<1.5&&Math.abs(s.get(1))<2&&Math.abs(s.get(2))<1.5),"original seat attachments remain on boat");
            if(capacity==2)require(boat.getPassengers().get(0).position().distanceToSqr(boat.getPassengers().get(1).position())>.1,"two original seats differ");
            var cargo=new ItemStack(Items.DIAMOND,3);cargo.set(DataComponents.CUSTOM_NAME,Component.literal("P11 cargo"));
            if(boat instanceof Container container){container.setItem(0,cargo.copy());container.setItem(container.getContainerSize()-1,new ItemStack(Items.APPLE,7));}
            // Native proxies will be deliberately transient. This baseline saves the original boat and cargo.
            boat.ejectPassengers();
            var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());boat.saveWithoutId(out);
            var clone=(AbstractBoat)entry.type().create(level,EntitySpawnReason.LOAD);
            require(clone!=null,"load original boat");clone.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),out.buildResult()));
            require(clone.getUUID().equals(boat.getUUID())&&clone.position().equals(boat.position())&&clone.getYRot()==boat.getYRot(),"boat identity/position/yaw persisted "+entry.id());
            if(clone instanceof Container container){require(ItemStack.matches(container.getItem(0),cargo)&&container.getItem(container.getContainerSize()-1).getCount()==7,"chest cargo components/counts persisted");}
            reports.add(Map.of("kind","boat_baseline","type",entry.id().toString(),"capacity",capacity,"seats",seats,"cargo_slots",boat instanceof Container c?c.getContainerSize():0,"nbt_roundtrip",true));
            // A low hull fits under this obstacle, but an upright Sifu passenger
            // must stop at it. Empty boats retain the installed vanilla result.
            var proxy=new dev.sifucraft.combat.EnemyProxy(dev.sifucraft.combat.EnemyEntities.ENEMY_PROXY,level);
            proxy.setPos(24.5,201,8.5);require(proxy.startRiding(boat),"test passenger boards "+entry.id());
            var obstacle=new BlockPos(26,202,8);level.setBlockAndUpdate(obstacle,Blocks.STONE.defaultBlockState());
            var requested=new Vec3(3,0,0);
            var hull=net.minecraft.world.entity.Entity.collideBoundingBox(boat,requested,boat.getBoundingBox(),level,List.of());
            var occupied=dev.sifucraft.combat.BoatPassengers.constrainBoat(boat,requested);
            require(hull.equals(requested)&&occupied.x>0&&occupied.x<2&&occupied.y==0&&occupied.z==0,"upright passenger clearance differs from hull "+entry.id());
            require(Math.abs(proxy.getBoundingBox().maxX+occupied.x-26)<1e-6,"passenger stops at the actual obstacle face "+entry.id());
            var rider=cows.getFirst();rider.setPos(boat.position());
            var exit=dev.sifucraft.combat.BoatPassengers.dismount(boat,rider,boat.position());
            require(!exit.equals(boat.position())&&!rider.getBoundingBox().move(exit.subtract(rider.position())).intersects(boat.getBoundingBox().minmax(proxy.getBoundingBox())),"water fallback avoids occupied native boat "+entry.id());
            proxy.stopRiding();require(dev.sifucraft.combat.BoatPassengers.constrainBoat(boat,requested).equals(requested),"empty boat is unmodified "+entry.id());
            require(dev.sifucraft.combat.BoatPassengers.dismount(boat,rider,boat.position()).equals(boat.position()),"ordinary empty-boat dismount is unchanged "+entry.id());
            proxy.discard();level.setBlockAndUpdate(obstacle,Blocks.AIR.defaultBlockState());
            for(var cow:cows){cow.stopRiding();cow.discard();}boat.discard();clone.discard();
        }
        require(!reports.isEmpty(),"installed boats enumerated");
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[isolated-systems-test] boat baseline PASS: {} installed boat/raft types, vanilla auto-boarding/capacity/seats/cargo NBT",reports.size());
        return reports;
    }
}
