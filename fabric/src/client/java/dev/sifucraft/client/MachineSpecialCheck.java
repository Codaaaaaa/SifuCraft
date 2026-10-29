package dev.sifucraft.client;

import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.combat.MachineProjectiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Installed machine products and real vanilla damage; no IPC or claimed UE execution. */
final class MachineSpecialCheck {
    private static final class Target extends Cow {
        DamageSource damage;float loss;
        Target(ServerLevel level){super(EntityTypes.COW,level);setNoAi(true);setNoGravity(true);}
        @Override public boolean hurtServer(ServerLevel level,DamageSource source,float amount){float before=getHealth();boolean result=super.hurtServer(level,source,amount);if(result){damage=source;loss+=before-getHealth();}return result;}
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("machine products: "+why);}
    private static net.minecraft.nbt.CompoundTag save(Entity entity,ServerLevel level){var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());entity.saveWithoutId(out);return out.buildResult();}
    static List<Map<String,Object>> run(ServerLevel level){
        var previous=WorldMotionWorld.current();var requests=new WorldMotionRequests((mc,nativeSession)->{throw new AssertionError("fixture never opens a native mapping");});
        var scope=new WorldMotionRequests.Scope(901,902,903,PistonMovePlans.saveKey(level),ShapeProtocol.mapHash("ProductsFixture"));requests.attach(scope);
        var view=new WorldMotionWorld.View(requests,scope,new MapPlacement("ProductsFixture",0,0,0),null,null,null,new WorldMotionWorld.Geometry(0,0,0,false));
        var pos=new BlockPos(24,205,8);var original=level.getBlockState(pos);require(!original.hasBlockEntity(),"no user container replaced");
        var reports=new ArrayList<Map<String,Object>>();var cleanup=new ArrayList<Entity>();
        try{
            WorldMotionWorld.offer(view);
            for(int kind=0;kind<3;kind++){
                var stack=new ItemStack(kind==0?Items.SPLASH_POTION:kind==1?Items.LINGERING_POTION:Items.FIREWORK_ROCKET,2);
                if(kind<2)stack.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.HARMING));else stack.set(DataComponents.FIREWORKS,new Fireworks(0,List.of(FireworkExplosion.DEFAULT)));
                level.removeBlockEntity(pos);level.setBlockAndUpdate(pos,Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST));
                var container=(Container)level.getBlockEntity(pos);container.setItem(0,stack.copyWithCount(1));container.setItem(1,stack.copyWithCount(1));
                var before=new HashSet<java.util.UUID>();for(var e:level.getAllEntities())before.add(e.getUUID());
                var products=new ArrayList<Projectile>();MachineProjectiles.observe(products::add);
                // The installed scheduled BlockState.tick executes its entire dispenseFrom path.
                level.getBlockState(pos).tick(level,pos,level.getRandom());
                MachineProjectiles.observe(null);require(products.size()==1,"one successfully registered machine product: "+products.size());var product=products.getFirst();
                int remaining=0;for(int slot=0;slot<container.getContainerSize();slot++)remaining+=container.getItem(slot).getCount();
                require(remaining==1,"one item consumed for one original product");cleanup.add(product);
                var credential=((MachineProjectiles.Carrier)product).sifucraft$machineOrigin();require(credential!=null&&credential.save()==scope.save()&&credential.map()==scope.map()&&credential.pos().equals(pos),"actual dispense signs source");
                var clone=product.getType().create(level,net.minecraft.world.entity.EntitySpawnReason.LOAD);require(clone!=null,"original product load");cleanup.add(clone);
                clone.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),save(product,level)));
                require(credential.equals(((MachineProjectiles.Carrier)clone).sifucraft$machineOrigin()),"full product source survives vanilla NBT");
                var target=new Target(level);target.setPos(product.position().add(1,-.5,0));cleanup.add(target);require(level.addFreshEntity(target),"registered original target");
                var proxy=new EnemyProxy(EnemyEntities.ENEMY_PROXY,level);cleanup.add(proxy);
                if(product instanceof ThrownSplashPotion splash)splash.onHitAsPotion(level,splash.getItem(),BlockHitResult.miss(product.position(),Direction.UP,pos));
                else if(product instanceof ThrownLingeringPotion lingering){
                    lingering.onHitAsPotion(level,lingering.getItem(),BlockHitResult.miss(product.position(),Direction.UP,pos));AreaEffectCloud cloud=null;
                    for(var e:level.getAllEntities())if(e instanceof AreaEffectCloud c&&!before.contains(e.getUUID())){require(cloud==null,"one original lingering cloud");cloud=c;}
                    require(cloud!=null&&credential.equals(((MachineProjectiles.Carrier)cloud).sifucraft$machineOrigin()),"original cloud inherits the same credential");cleanup.add(cloud);
                    var copy=new AreaEffectCloud(EntityTypes.AREA_EFFECT_CLOUD,level);copy.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),save(cloud,level)));cleanup.add(copy);
                    require(credential.equals(((MachineProjectiles.Carrier)copy).sifucraft$machineOrigin())&&copy.getRadius()==cloud.getRadius(),"cloud origin/radius survive original NBT");
                    for(int i=0;i<15;i++)level.tickNonPassenger(cloud);
                }else if(product instanceof FireworkRocketEntity rocket){
                    var tag=save(rocket,level);tag.putInt("LifeTime",0);tag.putInt("Life",1);rocket.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),tag));level.tickNonPassenger(rocket);
                }
                require(target.loss>0&&target.damage!=null,"original product actually hurts its original target");
                require(credential.equals(MachineProjectiles.evidence(proxy,target.damage)),"only the real damage source has this machine credential");
                var wrong=new WorldMotionRequests.Scope(901,902,903,scope.save(),scope.map()+1);WorldMotionWorld.offer(new WorldMotionWorld.View(requests,wrong,view.placement(),null,null,null,view.geometry()));
                require(MachineProjectiles.evidence(proxy,target.damage)==null,"old map product cannot authorize a new map");WorldMotionWorld.offer(view);
                reports.add(Map.of("kind",kind,"mc_final_damage",target.loss,"damage_type",target.damage.typeHolder().unwrapKey().orElseThrow().identifier().toString(),"product_nbt",true,"cloud_nbt",kind==1));
                for(var entity:cleanup)entity.discard();cleanup.clear();
            }
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[machine-special-test] PASS original splash/lingering/firework products, consumption, actual damage, cloud/product NBT, old map refusal: {}",reports);return reports;
        }finally{MachineProjectiles.observe(null);for(var e:cleanup)e.discard();level.removeBlockEntity(pos);level.setBlock(pos,original,2);WorldMotionWorld.offer(previous);requests.close();}
    }
}
