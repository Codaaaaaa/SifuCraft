package dev.sifucraft.client;

import com.mojang.authlib.GameProfile;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Opt-in actual MC environment contacts. Damage events are observed and always allowed. */
final class PlayerEnvironmentCheck {
    private record Spec(String name,String hazard,String protection,boolean immune) {}
    private record Hit(DamageSource source,float amount) {}
    private static final List<Spec> CASES=List.of(
        new Spec("lava_plain","lava","none",false),new Spec("lava_armor","lava","armor",false),
        new Spec("lava_protection","lava","protection",false),new Spec("lava_resistance","lava","resistance",false),
        new Spec("lava_absorption","lava","absorption",false),new Spec("lava_fire_resistance","lava","fire_resistance",true),
        new Spec("lava_totem","lava","totem",false),new Spec("hotfloor_plain","hotFloor","none",false),
        new Spec("hotfloor_protection","hotFloor","protection",false),new Spec("hotfloor_fire_resistance","hotFloor","fire_resistance",true),
        new Spec("hotfloor_frost_walker","hotFloor","frost_walker",true),
        new Spec("drown_plain","drown","none",false),new Spec("drown_resistance","drown","resistance",false),
        new Spec("drown_absorption","drown","absorption",false),new Spec("drown_water_breathing","drown","water_breathing",true),
        new Spec("feet_only_water","feet_water","none",true),new Spec("fire_plain","onFire","none",false),
        new Spec("fire_fire_resistance","onFire","fire_resistance",true),new Spec("water_extinguishes","extinguish","none",false),
        new Spec("fall_plain","fall","none",false),new Spec("fall_feather_falling","fall","feather_falling",false),
        new Spec("fall_slow_falling","fall","slow_falling",true));
    private static final class Reference extends Player {
        Reference(ServerLevel level,int seed){super(level,new GameProfile(new UUID(0x454e565245464552L,seed),"EnvironmentRef"));getRandom().setSeed(seed);}
        @Override public GameType gameMode(){return GameType.SURVIVAL;}
    }
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private final List<ItemStack> inventory=new ArrayList<>();private final List<MobEffectInstance> effects=new ArrayList<>();
    private volatile boolean ready,finished,atChamber;private volatile Vec3 target;
    private ServerPlayer player;private ServerLevel level;private Reference reference;private MinecraftServer server;
    private Vec3 originalPosition;private float yaw,pitch,health,absorption;private int originalAir,originalFire;
    private GameType mode;private Difficulty difficulty;private boolean regen,spawns,keep;
    private int stage,warmup,index,ticks;private long deadline;private Hit observed;private Spec current;
    private final BlockPos base=new BlockPos(80,67,18);
    PlayerEnvironmentCheck(){
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-player-environment-check-"),"disposable save required");
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->{
            if(stage==3&&entity==player&&observed==null&&current!=null&&(source.getMsgId().equals(current.hazard)||current.hazard.equals("extinguish")&&source.getMsgId().equals("onFire")))observed=new Hit(source,amount);
            return true;
        });
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("player environment: "+why);}
    void clientTick(Minecraft mc,boolean gui){
        if(finished){mc.stop();return;}
        var view=WorldMotionWorld.current();ready=gui&&view!=null&&view.placement().map().equals("MotionFixture")&&view.shapesCurrent()&&SceneFluidGeometry.available(mc.level);
        var destination=target;atChamber=mc.player!=null&&destination!=null&&mc.player.position().distanceTo(destination)<.15;
    }
    private void put(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity(),"no saved BE replaced");originals.putIfAbsent(pos.immutable(),old);level.setBlockAndUpdate(pos,state);}
    private void chamber(){
        put(base,Blocks.GLASS.defaultBlockState());
        for(int y=1;y<=12;y++){
            var p=base.above(y);for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL)put(p.relative(d),Blocks.GLASS.defaultBlockState());
            put(p,Blocks.AIR.defaultBlockState());
        }
        put(base.above(12),Blocks.GLASS.defaultBlockState());
    }
    private void teleport(Vec3 p){target=p;player.teleportTo(level,p.x,p.y,p.z,java.util.Set.of(),yaw,pitch,true);}
    private void equipment(Player p,String protection){
        for(var slot:EquipmentSlot.values())p.setItemSlot(slot,ItemStack.EMPTY);
        if(protection.equals("armor")||protection.equals("protection")){
            p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
        }
        if(protection.equals("protection"))for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))
            p.getItemBySlot(slot).enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION),4);
        if(protection.equals("feather_falling")||protection.equals("frost_walker")){
            var boots=new ItemStack(Items.DIAMOND_BOOTS);boots.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(protection.equals("feather_falling")?Enchantments.FEATHER_FALLING:Enchantments.FROST_WALKER),protection.equals("feather_falling")?4:2);p.setItemSlot(EquipmentSlot.FEET,boots);
        }
        if(protection.equals("totem"))p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));
    }
    private void reset(Player p){
        p.removeAllEffects();p.clearFire();p.setAirSupply(p.getMaxAirSupply());p.setHealth(p.getMaxHealth());p.setAbsorptionAmount(0);
        equipment(p,current.protection);
        switch(current.protection){
            case "resistance"->p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,20000,1));
            case "absorption"->p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,20000,1));
            case "fire_resistance"->p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,20000));
            case "water_breathing"->p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,20000));
            case "slow_falling"->p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,20000));
            case "totem"->p.setHealth(1);
            default->{}
        }
    }
    private void prepare(){
        current=CASES.get(index);chamber();player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying=false;player.onUpdateAbilities();reset(player);
        if(reference!=null)reference.discard();reference=new Reference(level,1000+index);reference.setPos(originalPosition);reset(reference);
        // Original equipment attribute collection runs once before reference damage.
        level.tickNonPassenger(reference);resetHealth(reference);
        teleport(Vec3.atBottomCenterOf(base.above()).add(0,.01,0));warmup=0;observed=null;ticks=0;stage=2;
    }
    private void resetHealth(Player p){p.setHealth(current.protection.equals("totem")?1:p.getMaxHealth());}
    private void activate(){
        resetHealth(player);player.setAbsorptionAmount(reference.getAbsorptionAmount());observed=null;stage=3;ticks=0;
        switch(current.hazard){
            case "lava"->{for(int y=1;y<=4;y++)put(base.above(y),Blocks.LAVA.defaultBlockState());}
            case "hotFloor"->put(base,Blocks.MAGMA_BLOCK.defaultBlockState());
            case "drown"->{for(int y=1;y<=4;y++)put(base.above(y),Blocks.WATER.defaultBlockState());player.setAirSupply(0);}
            case "feet_water"->put(base.above(),Blocks.WATER.defaultBlockState());
            case "onFire"->player.setRemainingFireTicks(160);
            case "extinguish"->{for(int y=1;y<=4;y++)put(base.above(y),Blocks.WATER.defaultBlockState());reference.setPos(player.position());reference.setDeltaMovement(player.getDeltaMovement());reference.setRemainingFireTicks(160);player.setRemainingFireTicks(160);}
            case "fall"->teleport(Vec3.atBottomCenterOf(base.above()).add(0,5.2,0));
            default->throw new AssertionError(current);
        }
    }
    private static Map<String,Integer> effectSignature(Player p){var result=new java.util.TreeMap<String,Integer>();for(var e:p.getActiveEffects())result.put(e.getEffect().getRegisteredName(),e.getAmplifier());return result;}
    private void compare(){
        require(observed!=null,"actual original source event "+current.name);
        require(reference.hurtServer(level,observed.source,observed.amount),"independent original Player accepted damage "+current.name);
        require(Math.abs(player.getHealth()-reference.getHealth())<.0001&&Math.abs(player.getAbsorptionAmount()-reference.getAbsorptionAmount())<.0001,"HP/absorption "+current.name+" actual="+player.getHealth()+"/"+player.getAbsorptionAmount()+" reference="+reference.getHealth()+"/"+reference.getAbsorptionAmount());
        for(var slot:EquipmentSlot.values())require(ItemStack.matches(player.getItemBySlot(slot),reference.getItemBySlot(slot)),"equipment/totem "+slot+" "+current.name);
        require(effectSignature(player).equals(effectSignature(reference)),"postdamage effects "+current.name);
        result();
    }
    private void result(){
        rows.add(Map.of("case",current.name,"ticks",ticks,"raw_damage",observed==null?0:observed.amount,"health",player.getHealth(),"absorption",player.getAbsorptionAmount(),"effects",effectSignature(player),"immune",current.immune));
        LoggerFactory.getLogger("sifucraft").info("[player-environment-test] {} ticks={} raw={} health={} absorption={} effects={}",current.name,ticks,observed==null?0:observed.amount,player.getHealth(),player.getAbsorptionAmount(),effectSignature(player));
        stage=1;player.clearFire();if(++index<CASES.size()){prepare();return;}
        restore(server);
        try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-player-environment.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","backend","actual MC client/server, synthetic Native endpoint; not UE damage","cases",rows),out));}catch(Exception e){throw new IllegalStateException(e);}
        finished=true;LoggerFactory.getLogger("sifucraft").info("[player-environment-test] PASS {} actual environment/protection cases; restored",rows.size());
    }
    void tick(MinecraftServer s){
        if(finished)return;
        try{
            if(stage==0){
                if(!ready||s.getPlayerList().getPlayers().isEmpty())return;server=s;level=s.overworld();player=s.getPlayerList().getPlayers().getFirst();
                originalPosition=player.position();yaw=player.getYRot();pitch=player.getXRot();health=player.getHealth();absorption=player.getAbsorptionAmount();mode=player.gameMode();difficulty=level.getDifficulty();originalAir=player.getAirSupply();originalFire=player.getRemainingFireTicks();
                for(int i=0;i<player.getInventory().getContainerSize();i++)inventory.add(player.getInventory().getItem(i).copy());for(var e:player.getActiveEffects())effects.add(new MobEffectInstance(e));
                var rules=s.getGameRules();regen=rules.get(GameRules.NATURAL_HEALTH_REGENERATION);spawns=rules.get(GameRules.SPAWN_MOBS);keep=rules.get(GameRules.KEEP_INVENTORY);
                rules.set(GameRules.NATURAL_HEALTH_REGENERATION,false,s);rules.set(GameRules.SPAWN_MOBS,false,s);rules.set(GameRules.KEEP_INVENTORY,true,s);s.setDifficulty(Difficulty.NORMAL,true);
                deadline=System.currentTimeMillis()+240000;stage=1;return;
            }
            require(System.currentTimeMillis()<deadline,"bounded fixture deadline");
            if(stage==1){if(++warmup>=220&&ready)prepare();return;}
            if(stage==2){if(atChamber&&ready&&++warmup>=30)activate();return;}
            if(stage==3){
                ++ticks;require(player.isAlive(),"no unexpected player death "+current.name);
                if(current.hazard.equals("extinguish")){
                    level.tickNonPassenger(reference);
                    if(ticks<80)return;
                    require(!player.isOnFire()&&!reference.isOnFire(),"actual/reference water extinguished fire");
                    require(player.getHealth()==reference.getHealth(),"water entry tick order matches original Player actual="+player.getHealth()+" reference="+reference.getHealth());
                    result();return;
                }
                boolean changed=player.getHealth()!=(current.protection.equals("totem")?1:player.getMaxHealth())||player.getAbsorptionAmount()!=reference.getAbsorptionAmount()||current.protection.equals("totem")&&player.getOffhandItem().isEmpty();
                if(changed){require(!current.immune,"original protection blocks damage "+current.name);compare();return;}
                if(current.immune&&ticks>=80){require(current.hazard.equals("fall")?player.onGround():true,"slow fall actually landed");if(current.hazard.equals("extinguish"))require(!player.isOnFire(),"actual water extinguished fire");result();return;}
                require(ticks<180,"actual hazard produced damage "+current.name+" air="+player.getAirSupply()+" fire="+player.getRemainingFireTicks()+" y="+player.getY());
            }
        }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[player-environment-test] FAIL",e);restore(s);finished=true;Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}
    }
    void restore(MinecraftServer s){
        stage=-1;target=null;if(reference!=null)reference.discard();reference=null;
        if(level!=null)for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();
        if(player==null)return;
        player.removeAllEffects();for(var e:effects)player.addEffect(new MobEffectInstance(e));for(int i=0;i<inventory.size();i++)player.getInventory().setItem(i,inventory.get(i).copy());
        player.setGameMode(mode);player.setHealth(health);player.setAbsorptionAmount(absorption);player.setAirSupply(originalAir);player.setRemainingFireTicks(originalFire);teleport(originalPosition);target=null;
        var rules=s.getGameRules();rules.set(GameRules.NATURAL_HEALTH_REGENERATION,regen,s);rules.set(GameRules.SPAWN_MOBS,spawns,s);rules.set(GameRules.KEEP_INVENTORY,keep,s);s.setDifficulty(difficulty,true);player=null;
    }
}
