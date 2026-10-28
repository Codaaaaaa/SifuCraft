package dev.sifucraft.client;

import dev.sifucraft.collision.CollisionGrid;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.storage.AtomicFiles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.LoggerFactory;

/** Disposable, bridge-free checks of installed vanilla code with the production mixins active. */
final class IsolatedSystemsCheck {
    private record Result(float exposure, float health, Vec3 velocity, List<String> blocks) {}
    private final List<Map<String,Object>> reports = new ArrayList<>();
    private boolean started, hidden;
    private volatile boolean finished;
    private int stage;
    private long deadline = System.currentTimeMillis() + 120000;

    static void install() {
        require(DevWorld.enabled() && DevWorld.LEVEL_ID.startsWith("sifucraft-isolated-systems-check-"), "disposable isolated save required");
        var check = new IsolatedSystemsCheck();
        ClientTickEvents.END_CLIENT_TICK.register(check::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(check::tick);
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError("isolated systems: " + why); }
    private void clientTick(Minecraft mc) {
        require(System.currentTimeMillis() < deadline, "bounded fixture deadline");
        if (finished) { mc.stop(); return; }
        mc.options.pauseOnLostFocus = false;
        if (!hidden) { SDLVideo.SDL_HideWindow(mc.getWindow().handle()); hidden = true; }
        if (!started && mc.level == null && mc.gui.overlay() == null && mc.gui.screen() != null) {
            started = true;
            if (mc.options.onboardAccessibility) mc.options.onboardingAccessibilityFinished();
            DevWorld.enter(mc);
        }
    }

    private SifuCollision.Active wall(ServerLevel level, int height) throws Exception {
        byte[] name = "ExplosionFixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int count = height * 21;
        var bytes = ByteBuffer.allocate(36 + name.length + count * 76).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8);
        bytes.putFloat(0).putFloat(0).putFloat(0).putInt(name.length).put(name).putInt(count);
        for (int y = 201; y < 201 + height; y++) for (int z = -10; z <= 10; z++) {
            bytes.putInt(7).putInt(z).putInt(y);
            for (int layer = 0; layer < 8; layer++) bytes.putLong(-1L);
        }
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("explosion-wall.scvx");
        Files.write(path, bytes.array());
        return new SifuCollision.Active(CollisionGrid.read(path), new MapPlacement("ExplosionFixture",0,0,0));
    }

    private Result explosion(ServerLevel level, int height, boolean nativeWall, boolean wet) throws Exception {
        SifuCollision.set(null);
        for (int x = -4; x <= 14; x++) for (int z = -10; z <= 10; z++) for (int y = 200; y <= 210; y++) {
            var state = y == 200 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (x == 7 && y > 200 && y <= 200 + height && !nativeWall) state = Blocks.BEDROCK.defaultBlockState();
            if (x >= 9 && x <= 11 && y == 201 && z >= -2 && z <= 2) state = Blocks.STONE.defaultBlockState();
            level.setBlock(new BlockPos(x,y,z), state, 2);
        }
        if (wet) for (int x = 3; x <= 5; x++) for (int y = 201; y <= 203; y++) for (int z = -1; z <= 1; z++)
            level.setBlock(new BlockPos(x,y,z), Blocks.WATER.defaultBlockState(), 2);
        if (nativeWall) SifuCollision.set(wall(level,height));
        var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        require(cow != null, "vanilla reference entity");
        cow.setNoAi(true); cow.setPos(10.5,202,.5); cow.setDeltaMovement(Vec3.ZERO);
        require(level.addFreshEntity(cow), "registered explosion target");
        var center = new Vec3(4.5,202,.5);
        float exposure = ServerExplosion.getSeenPercent(center,cow);
        level.getRandom().setSeed(0x503130);
        var explosion=new ServerExplosion(level,null,null,null,center,4,false,Explosion.BlockInteraction.DESTROY);
        explosion.explode();
        float healthAfter=cow.getHealth();var velocityAfter=cow.getDeltaMovement();
        explosion.explode();
        require(cow.getHealth()==healthAfter&&cow.getDeltaMovement().equals(velocityAfter),"the same explosion object cannot apply damage or impulse twice");
        var states = new ArrayList<String>();
        for (int x = 9; x <= 11; x++) for (int z = -2; z <= 2; z++)
            states.add(level.getBlockState(new BlockPos(x,201,z)).toString());
        var result = new Result(exposure,cow.getHealth(),cow.getDeltaMovement(),states);
        cow.discard(); SifuCollision.set(null);
        // Test the original ray calculator, not registered drops that could contaminate the next case.
        for (var e : level.getAllEntities()) if (e instanceof net.minecraft.world.entity.item.ItemEntity) e.discard();
        return result;
    }

    private void explosions(ServerLevel level) throws Exception {
        for (int height : new int[]{0,1,2,6}) for (boolean wet : new boolean[]{false,true}) {
            var vanilla = explosion(level,height,false,wet);
            var nativeResult = explosion(level,height,true,wet);
            require(vanilla.exposure == nativeResult.exposure, "original exposure h="+height+" wet="+wet+" "+vanilla+" vs "+nativeResult);
            require(vanilla.health == nativeResult.health && vanilla.velocity.distanceTo(nativeResult.velocity) < 1e-12,
                    "original damage and 3D impulse h="+height+" wet="+wet);
            require(vanilla.blocks.equals(nativeResult.blocks), "original destructive rays h="+height+" wet="+wet);
            reports.add(Map.of("kind","explosion_wall","height",height,"water",wet,"exposure",nativeResult.exposure,
                    "health",nativeResult.health,"velocity",List.of(nativeResult.velocity.x,nativeResult.velocity.y,nativeResult.velocity.z),
                    "stone_survived",nativeResult.blocks.stream().filter(s->s.contains("stone")).count()));
        }
        require(((Number) reports.getFirst().get("exposure")).floatValue() > 0, "unobstructed control is exposed");
        require(((Number) reports.getLast().get("exposure")).floatValue() == 0, "full Native wall blocks exposure");
        LoggerFactory.getLogger("sifucraft").info("[isolated-systems-test] explosion PASS: 8 native/original wall pairs, water, partial/full wall, HP, 3D impulse, destructive rays");
    }

    private void tntLifecycle(ServerLevel level) {
        var tnt = new net.minecraft.world.entity.item.PrimedTnt(EntityTypes.TNT,level);
        tnt.setPos(4.5,206,.5); tnt.setDeltaMovement(.4,.3,.2); tnt.setFuse(8);
        var originalPosition=tnt.position(); var originalVelocity=tnt.getDeltaMovement();
        dev.sifucraft.blocks.SceneFluidGeometry.publish(true,false,null);
        try {
            for(int i=0;i<12;i++)level.tickNonPassenger(tnt);
            require(tnt.getFuse()==8 && tnt.position().equals(originalPosition) && tnt.getDeltaMovement().equals(originalVelocity),
                    "pending geometry preserves the whole TNT tick, not only the final blast");
        } finally { dev.sifucraft.blocks.SceneFluidGeometry.publish(false,true,null); }
        level.tickNonPassenger(tnt);
        require(tnt.getFuse()==7 && !tnt.position().equals(originalPosition), "original fuse and trajectory resume once geometry is ready");
        var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
        tnt.saveWithoutId(out);
        var loaded=new net.minecraft.world.entity.item.PrimedTnt(EntityTypes.TNT,level);
        loaded.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),out.buildResult()));
        require(loaded.getUUID().equals(tnt.getUUID()) && loaded.getFuse()==7 && loaded.position().equals(tnt.position()) &&
                loaded.getDeltaMovement().equals(tnt.getDeltaMovement()), "original TNT UUID/fuse/position/velocity round trip");
        reports.add(Map.of("kind","tnt_lifecycle","pending_ticks",12,"fuse_restored",loaded.getFuse(),"uuid_preserved",true));
        tnt.discard(); loaded.discard();
        LoggerFactory.getLogger("sifucraft").info("[isolated-systems-test] TNT lifecycle PASS: geometry wait, original resume, UUID/fuse/3D velocity NBT");
    }

    private void tick(MinecraftServer server) {
        if (finished || stage != 0 || server.getPlayerList().getPlayers().isEmpty()) return;
        if (!server.overworld().isPositionEntityTicking(new BlockPos(24,201,8))) return;
        stage = 1;
        try {
            if(Boolean.getBoolean("sifucraft.machineSpecialSelfTest"))reports.addAll(MachineSpecialCheck.run(server.overworld()));
            else {
                explosions(server.overworld());
                tntLifecycle(server.overworld());
                reports.addAll(BoatBaselineCheck.run(server.overworld()));
            }
            var report = new LinkedHashMap<String,Object>(); report.put("passed",true); report.put("bridge_connected",false); report.put("cases",reports);
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("sifucraft-isolated-systems-report.json"),
                    out -> new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
            finished = true;
        } catch (Exception e) { throw new RuntimeException(e); }
        finally { SifuCollision.set(null); }
    }
}
