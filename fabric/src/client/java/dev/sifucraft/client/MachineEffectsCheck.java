package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.*;
import dev.sifucraft.collision.*;
import dev.sifucraft.combat.*;
import dev.sifucraft.combat.WorldEffects;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import static dev.sifucraft.bridge.WorldMotionProtocol.*;

/** Actual scheduled world ticks and dispenser potions; the damage endpoint is in memory, never UE. */
final class MachineEffectsCheck {
    private final ServerLevel level;
    private final List<Map<String,Object>> reports=new ArrayList<>();
    private final List<Command> commands=new ArrayList<>();
    private final ArrayDeque<Reply> replies=new ArrayDeque<>();
    private final WorldMotionRequests requests;
    private final WorldMotionRequests.Scope scope;
    private final MapPlacement placement=new MapPlacement("EffectFixture",0,0,0);
    private final SifuCollision.Active scene;
    private final BlockPos pos=new BlockPos(24,205,8);
    private final net.minecraft.world.level.block.state.BlockState original;
    private Cow reference;
    private int kind=-1,ticks;
    private float hp=60;
    private long frame;
    MachineEffectsCheck(ServerLevel level)throws Exception{
        this.level=level;original=level.getBlockState(pos);require(!original.hasBlockEntity(),"disposable position");
        scope=new WorldMotionRequests.Scope(901,902,903,PistonMovePlans.saveKey(level),ShapeProtocol.mapHash(placement.map()));
        requests=new WorldMotionRequests((mc,nativeSession)->new WorldMotionRequests.Transport(){
            public boolean publish(Command c,long now){
                require(c.op()==DAMAGE&&c.source()==WORLD_EFFECT,"only genuine captured damage");
                require(commands.stream().noneMatch(old->old.id()==c.id()),"pump has no unintended retry");
                commands.add(c);hp-=c.amount()*Protocol.ENEMY_SIFU_HP_PER_MC_HP;
                replies.add(new Reply(c.id(),c.lease(),frame,now,c.world(),c.target(),c.spawn(),2,1,0,1,hash(encode(c),COMMAND_BYTES-8),c.position(),SifuFrame.Vec3.ZERO,hp,0,1));return true;
            }
            public Reply poll(){return replies.poll();}
            public WorldMotionMailbox.NativeStatus status(long now){return new WorldMotionMailbox.NativeStatus(DAMAGE_CAPABILITY,now,scope.world(),0,0,1);}
            public void close(){}
        });requests.attach(scope);
        byte[] name=placement.map().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var b=ByteBuffer.allocate(36+name.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8).putFloat(0).putFloat(0).putFloat(0).putInt(name.length).put(name).putInt(0);
        Path path=level.getServer().getWorldPath(LevelResource.ROOT).resolve("effects.scvx");Files.write(path,b.array());
        scene=new SifuCollision.Active(CollisionGrid.read(path),placement,null,scope.nativeSession(),scope.world());
        SifuCollision.set(scene);publish();
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("machine effects: "+why);}
    private EnemyProxy target(){return EnemyMirrors.hitAttacker(101,1);}
    private Holder<MobEffect> type(){return kind==1?MobEffects.WITHER:MobEffects.POISON;}
    private void publish(){
        requests.pump(WorldWriter.clockMillis());requests.pump(WorldWriter.clockMillis());
        var location=new SifuFrame.Vec3(2610,850,20592);
        var enemies=new EnemyFrame(scope.nativeSession(),++frame,scope.world(),System.currentTimeMillis(),List.of(new EnemyFrame.Entry(101,1,location,0,35,92,hp,60,Protocol.ENEMY_ALIVE,0,SifuFrame.Vec3.ZERO)));
        WorldMotionWorld.offer(new WorldMotionWorld.View(requests,scope,placement,enemies,scene,requests.status(),new WorldMotionWorld.Geometry(1,1,1,true)));
        var player=new SifuFrame(frame,0,scope.world(),Protocol.SNAPSHOT_HAS_PAWN,location,SifuFrame.Vec3.ZERO,SifuFrame.Vec3.ZERO,location,SifuFrame.Vec3.ZERO,90,180,120,"/SifuCraft/Test/Effects",0,0,0,Protocol.OWNER_SIFU,90,1);
        EnemyMirrors.offer(player,enemies,placement,true);EnemyMirrors.tick(level.getServer());
    }
    private void begin(){
        kind++;ticks=0;commands.clear();hp=60;EnemyMirrors.stop();publish();
        reference=new Cow(EntityTypes.COW,level);reference.setNoAi(true);reference.setNoGravity(true);
        reference.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);reference.setHealth(20);
        reference.setPos(target().position().add(0,0,.8));require(level.addFreshEntity(reference),"registered original reference");
        var stack=new ItemStack(Items.SPLASH_POTION);stack.set(DataComponents.POTION_CONTENTS,PotionContents.EMPTY.withEffectAdded(new MobEffectInstance(type(),240,0)));
        level.setBlockAndUpdate(pos,Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST));
        var container=(Container)level.getBlockEntity(pos);container.setItem(0,stack);
        var products=new ArrayList<net.minecraft.world.entity.projectile.Projectile>();MachineProjectiles.observe(products::add);
        try{level.getBlockState(pos).tick(level,pos,level.getRandom());}finally{MachineProjectiles.observe(null);}
        require(products.size()==1&&products.getFirst() instanceof ThrownSplashPotion,"actual dispenser splash");
        var potion=(ThrownSplashPotion)products.getFirst();require(((MachineProjectiles.Carrier)potion).sifucraft$machineOrigin()!=null,"actual machine origin");
        // Match the target's exact splash distance for the independent original entity.
        reference.setPos(target().position());potion.setPos(target().position().add(0,1,0));
        potion.onHitAsPotion(level,potion.getItem(),BlockHitResult.miss(potion.position(),Direction.UP,pos));
        require(target().hasEffect(type())&&reference.hasEffect(type()),"original splash applied equal periodic effects");
        require(target().getEffect(type()).getDuration()==reference.getEffect(type()).getDuration(),"identical original duration");
        require(container.getItem(0).isEmpty(),"one potion consumed");
        require(!WorldEffects.canCapture(target(),level.damageSources().magic()),"naked magic outside effect tick refused");
    }
    boolean tick()throws Exception{
        publish();if(kind<0){begin();return false;}ticks++;
        require(Math.abs((60-hp)/3-(20-reference.getHealth()))<1e-5,"each tick agrees with original health tick="+ticks+" hp="+hp+" ref="+reference.getHealth());
        if(ticks==70){
            int remaining=target().getEffect(type()).getDuration();MachineEffects.flush(level.getServer());
            EnemyMirrors.stop();MachineEffects.open(level.getServer());publish();
            // Normal entity tick restores the credential and original effect before ticking it.
            require(target().getEffect(type())==null,"new activation proxy has no preloaded effect");
            reports.add(Map.of("case",kind,"saved_remaining",remaining,"proxy_recreated",true));
        }
        if(ticks<190)return false;
        require(!commands.isEmpty(),"actual periodic damage submitted");
        reports.add(Map.of("case",kind,"effect",type().unwrapKey().orElseThrow().identifier().toString(),"ticks",ticks,"mc_damage",(60-hp)/3,"events",commands.size(),"naked_magic_refused",true));
        reference.discard();target().removeAllEffects();EnemyMirrors.stop();
        if(kind==0){begin();return false;}
        // A source-free application never inherits a machine's authority.
        hp=60;publish();target().addEffect(new MobEffectInstance(MobEffects.WITHER,20,4));
        float before=hp;level.tickNonPassenger(target());publish();require(hp==before,"uncredentialed effect cannot submit damage");
        target().removeAllEffects();EnemyMirrors.stop();WorldMotionWorld.offer(null);requests.close();SifuCollision.set(null);
        level.removeBlockEntity(pos);level.setBlock(pos,original,2);
        org.slf4j.LoggerFactory.getLogger("sifucraft").info("[machine-effects-test] PASS actual poison/wither splash, 380 original world ticks, per-tick HP, disk credential/proxy recreation, uncredentialed/naked magic refusal: {}",reports);
        return true;
    }
    List<Map<String,Object>> reports(){return reports;}
}
