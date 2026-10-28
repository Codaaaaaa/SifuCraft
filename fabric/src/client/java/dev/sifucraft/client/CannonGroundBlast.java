package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.*;
import dev.sifucraft.combat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import org.slf4j.LoggerFactory;

/** One original ground TNT, same-event reference damage, original wall and MC target controls. */
final class CannonGroundBlast implements FluidMotion.Observer {
    private static final class Reference extends Cow {
        private EntityDimensions size;
        Reference(ServerLevel level,float width,float height){super(EntityTypes.COW,level);size=EntityDimensions.scalable(width,height);refreshDimensions();setNoAi(true);setNoGravity(true);setSilent(true);getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);setHealth(100);}
        @Override protected void registerGoals(){}
        @Override public EntityDimensions getDefaultDimensions(Pose pose){return size==null?EntityDimensions.scalable(.7f,1.84f):size;}
    }
    private final ServerLevel level;private final EnemyFrame.Entry initial;private final boolean open;
    private Reference reference,wallControl;private PrimedTnt tnt;
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final List<BlockPos> targets=new ArrayList<>();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final FluidMotion.Stats before=FluidMotion.stats();private final long armedAt=System.currentTimeMillis();
    private Vec3 referenceImpulse;private float referenceDamage,wallDamage,exposure;private int destroyed,acquired,completed;private boolean observed,closed;
    private final Vec3 center=new Vec3(-144.0218,64.35,62.625);
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("C4 ground blast: "+why);}
    CannonGroundBlast(ServerLevel level,WorldMotionWorld.View view,EnemyFrame.Entry target,boolean open){
        this.level=level;initial=target;this.open=open;
        try{
        var proxy=EnemyMirrors.hitAttacker(target.id(),target.spawn());require(proxy!=null,"actual Native damage target");
        require(proxy.position().distanceTo(center)>7&&proxy.position().distanceTo(center)<11,"ground target is nonlethally distant but within power6 range");
        require(level.getServer().getPlayerList().getPlayers().getFirst().position().distanceTo(center)>12.5,"ordinary player is outside both ground blast radii");
        reference=new Reference(level,proxy.getBbWidth(),proxy.getBbHeight());reference.setPos(proxy.position());require(level.addFreshEntity(reference),"registered co-located original reference");
        wallControl=new Reference(level,.7f,1.84f);boolean found=false;
        var base=new dev.sifucraft.collision.SifuCollision.Active(view.scene().baseGrid(),view.placement(),null,view.scope().nativeSession(),view.scope().world());
        search:for(int dx:new int[]{-2,2,-3,3,-4,4})for(int dz:new int[]{-2,2,-3,3,-4,4})for(int step=0;step<=10;step++){
            var at=center.add(dx,-.35+step*.125,dz);wallControl.setPos(at);
            if(!SceneFluidGeometry.knownVolume(level,wallControl.getBoundingBox())||!dev.sifucraft.collision.SifuCollision.withQueryView(view.scene(),()->level.noCollision(wallControl,wallControl.getBoundingBox())))continue;
            if(base.clip(at.add(0,.05,0),at.add(0,-.35,0))==null)continue;
            float seen=dev.sifucraft.collision.SifuCollision.withQueryView(base,()->ServerExplosion.getSeenPercent(center,wallControl));
            if(seen==0){found=true;break search;}
        }
        require(found&&level.addFreshEntity(wallControl),"supported original-wall control has zero installed vanilla exposure");
        for(int x=-147;x>=-148;x--)for(int z=61;z<=63;z++){
            var at=new BlockPos(x,65,z);
            if(!level.getBlockState(at).isAir()||!SceneBlockSupport.nativeShape(level,at).isEmpty()||SceneExplosions.blocked(base,center,Vec3.atCenterOf(at)))continue;
            originals.put(at,level.getBlockState(at));targets.add(at);level.setBlockAndUpdate(at,Blocks.GLASS.defaultBlockState());
        }
        require(!targets.isEmpty(),"ground glass target cells fit the original scene behind the door");
        tnt=new PrimedTnt(EntityTypes.TNT,level);tnt.setPos(center);tnt.setNoGravity(true);tnt.setDeltaMovement(Vec3.ZERO);tnt.setFuse(20);
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());tnt.saveWithoutId(out);var tag=out.buildResult();tag.putFloat("explosion_power",6);
        tnt.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),tag));
        SceneExplosions.observeBefore(blast->{
            if(blast.getDirectSourceEntity()!=tnt)return;
            var actual=EnemyMirrors.hitAttacker(initial.id(),initial.spawn());require(actual!=null,"same actual Native activation before original blast");
            reference.setPos(actual.position());reference.setDeltaMovement(Vec3.ZERO);
            level.getRandom().setSeed(0xC40A);
            exposure=ServerExplosion.getSeenPercent(center,reference);
        });
        SceneExplosions.observe(id->{
            if(!id.equals(tnt.getUUID()))return;
            observed=true;referenceDamage=100-reference.getHealth();referenceImpulse=reference.getDeltaMovement();wallDamage=100-wallControl.getHealth();
            destroyed=(int)targets.stream().filter(at->level.getBlockState(at).isAir()).count();
            LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] original {} blast observed exposure={} reference_damage={} impulse={} wall_damage={} destroyed={}/{}",open?"open":"closed",exposure,referenceDamage,referenceImpulse,wallDamage,destroyed,targets.size());
        });
        FluidMotion.observe(this);require(level.addFreshEntity(tnt),"original world-ticked TNT power6 and original fuse");
        report.put("state",open?"open":"closed");report.put("native_target",initial.key());report.put("native_health_before",initial.health());report.put("center",center.toString());report.put("power",6);report.put("ground_y",64);report.put("glass_targets",targets);report.put("wall_control",wallControl.position().toString());
        report.put("source","original world-ticked TNT at the ground impact point; cannon launch is a separate existing fixture");
        }catch(Exception|AssertionError failure){close();throw failure;}
    }
    @Override public void prepared(FluidMotion.Sample sample){
        if(closed||!sample.key().equals(initial.key()))return;
        require(open&&acquired++==0&&referenceImpulse!=null&&sample.velocity().distanceTo(referenceImpulse)<1e-5,"one actual post-blast flight receives exactly the same original impulse");
    }
    @Override public void simulated(FluidMotion.Sample sample){}
    @Override public void completed(FluidMotion.Sample sample){if(!closed&&sample.key().equals(initial.key()))completed++;}
    boolean tick(WorldMotionWorld.View view){
        require(System.currentTimeMillis()-armedAt<25000,"bounded original blast and actual Native recovery");
        if(!observed||FluidMotion.stats().active()!=0||view.requests().pending()!=0)return false;
        require(FluidMotion.stats().failed()==before.failed(),"no hidden failed post-blast motion authority");
        if(open&&(acquired==0||completed==0))return false;
        var current=view.enemies().entries().stream().filter(e->e.key().equals(initial.key())&&e.alive()).findFirst().orElse(null);require(current!=null,"same surviving actual Native target after ground blast");
        require(wallDamage==1&&wallControl.getDeltaMovement().lengthSqr()<1e-10,"zero original exposure retains vanilla's one-point damage floor and stops impulse");
        require(Math.abs(initial.health()-current.health()-referenceDamage*3)<.02,"same-event original final damage maps once to actual Native HP");
        require(FluidMotion.stats().failed()==before.failed(),"no hidden failed post-blast motion authority");
        if(open)require(referenceDamage>0&&referenceImpulse.lengthSqr()>1e-6&&destroyed>0&&acquired==1&&completed>0,"open original door admits damage, original impulse, MC target destruction and actual Native movement");
        else require(exposure==0&&referenceDamage==1&&referenceImpulse.lengthSqr()<1e-10&&destroyed==0&&acquired==0,"closed original door retains the vanilla one-point floor while stopping impulse and MC target rays");
        report.put("native_health_after",current.health());report.put("reference_damage_mc",referenceDamage);report.put("exposure",exposure);report.put("reference_impulse",referenceImpulse.toString());report.put("wall_damage_mc",wallDamage);report.put("destroyed_glass_targets",destroyed);report.put("native_acquired",acquired);report.put("native_completed_ticks",completed);report.put("motion",FluidMotion.stats());
        LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] {} ground blast verified {}",open?"open":"closed",report);return true;
    }
    Map<String,Object> report(){return Map.copyOf(report);}
    void close(){
        closed=true;SceneExplosions.observeBefore(null);SceneExplosions.observe(null);FluidMotion.observe(null);if(tnt!=null&&!tnt.isRemoved())tnt.discard();if(reference!=null)reference.discard();if(wallControl!=null)wallControl.discard();for(var entry:originals.entrySet())level.setBlock(entry.getKey(),entry.getValue(),18);
    }
}
