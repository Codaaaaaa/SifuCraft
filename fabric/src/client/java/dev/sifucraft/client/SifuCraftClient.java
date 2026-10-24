package dev.sifucraft.client;

import dev.sifucraft.blocks.PlacedBlocks;
import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.McState;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.CollisionGrid;
import dev.sifucraft.collision.CollisionLoader;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.combat.CombatHits;
import dev.sifucraft.combat.PlayerDamage;
import dev.sifucraft.bridge.PlayerHit;
import dev.sifucraft.bridge.RespawnHandshake;
import dev.sifucraft.bridge.DamageResult;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: attaches the bridge as the Minecraft endpoint and, every frame right after
 * Minecraft polls its own input, applies the input Sifu routes here.
 *
 * <p>Development system properties: {@code sifucraft.devWorld} enters a creative superflat world as
 * soon as the client has finished loading; {@code sifucraft.hideWindow} hides the Minecraft window
 * so Sifu keeps the foreground; {@code sifucraft.exitWithSifu} stops the client (saving the world)
 * when Sifu closes.
 */
public final class SifuCraftClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private static final long STATUS_EVERY_MS = Long.getLong("sifucraft.statusMs", 5000);
    private static final int MAX_LOGGED_INPUT = 200;
    // Hand-over teleport: where to stand (Minecraft player 0.6 wide; within its step height), when
    // it has landed, and when to send it again if it was lost.
    private static final double PLAYER_HALF_WIDTH_CM = 30.0;
    private static final double STEP_UP_CM = 60.0;
    private static final double STEP_DOWN_CM = 50.0;
    private static final double LANDED_HORIZONTAL_SQ = 0.05 * 0.05;
    private static final double LANDED_VERTICAL = 0.5;
    private static final long TELEPORT_RETRY_MS = 2000;
    private static final double FALL_RESCUE_BLOCKS = 20.0;
    private static final long RESCUE_COOLDOWN_MS = 1000;

    private static final Item[] DEV_HOTBAR = {Items.STONE, Items.GRASS_BLOCK, Items.DIRT,
            Items.OAK_LOG, Items.OAK_PLANKS, Items.COBBLESTONE, Items.BRICKS, Items.GLOWSTONE, Items.IRON_SWORD};

    private static volatile SifuCraftClient instance;

    private Minecraft minecraft;
    private McBridge bridge;
    private InputInjector input;
    private final OverlayCapture overlay = new OverlayCapture();
    private boolean crosshair;
    private CollisionLoader collision;
    private final UnrealSpace space = new UnrealSpace();
    private final RespawnHandshake respawn = new RespawnHandshake();
    private final MapSlots slots = new MapSlots(message -> LOG.info("[space] {}", message));
    private int teleportEpoch;
    private int teleportWorld;
    private Vec3 teleportTarget = Vec3.ZERO;
    private long teleportSentMs;
    private Vec3 lastGround;
    private long rescueSentMs;
    private String publishedBlocksMap;
    private int publishedBlocksVersion = -1;
    private int blockCountLogged = -1;
    private int publishedPalette = -1;
    private BlockAppearance appearance;
    private final Set<String> closedScreens = new HashSet<>();
    private boolean devWorldStarted;
    private boolean windowHidden;
    private long nextStatusMs;
    private int loggedInput;

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(EnemyEntities.ENEMY_PROXY, NoopRenderer::new);
        minecraft = Minecraft.getInstance();
        input = new InputInjector(minecraft);
        bridge = new McBridge(new McBridge.Listener() {
            @Override public void onDamageResult(DamageResult result) { CombatHits.result(result); }
            @Override public void onPlayerHit(long session, long eventId, PlayerHit hit) { PlayerDamage.receive(session, eventId, hit); }
            @Override
            public void log(String message) {
                LOG.info("[bridge] {}", message);
            }

            @Override
            public void onKey(int scancode, int action, int modifiers) {
                logInput("key", scancode, action, modifiers);
                input.key(scancode, action, modifiers);
            }

            @Override
            public void onMouseButton(int button, int action, int modifiers) {
                logInput("button", button, action, modifiers);
                input.mouseButton(button, action, modifiers);
            }

            @Override
            public void onOwnerChanged(int owner) {
                LOG.info("[input] owner -> {}", owner == Protocol.OWNER_MINECRAFT ? "MINECRAFT" : "sifu");
                input.setOwner(owner == Protocol.OWNER_MINECRAFT);
            }

            @Override
            public void onPeerConnection(boolean connected, String reason) {
                if (connected) return;
                input.setOwner(false);
                space.reset(); teleportEpoch = 0; lastGround = null;
                if ("peer closing".equals(reason) && Boolean.getBoolean("sifucraft.exitWithSifu")) {
                    LOG.info("[dev] Sifu went away, stopping the client");
                    minecraft.stop();
                }
            }
        });
        try {
            bridge.start();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("bridge unavailable, SifuCraft stays inactive", e);
            bridge = null;
            return;
        }
        collision = new CollisionLoader(message -> LOG.info("[collision] {}", message));
        appearance = new BlockAppearance(minecraft, message -> LOG.info("[blocks] {}", message));
        instance = this;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> PlacedBlocks.open(server.getWorldPath(LevelResource.ROOT),
                message -> LOG.info("[blocks] {}", message)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PlacedBlocks.close());
        ServerTickEvents.END_SERVER_TICK.register(server -> PlacedBlocks.saveIfDirty());
        if (Boolean.getBoolean("sifucraft.combatSelfTest")) {
            CombatCheck check = new CombatCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
        }
        if (Boolean.getBoolean("sifucraft.playerDamageTest") || Boolean.getBoolean("sifucraft.playerHitSelfTest") ||
                Boolean.getBoolean("sifucraft.respawnSelfTest")) {
            PlayerHitCheck check = new PlayerHitCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(check::restore);
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            overlay.close();
            collision.close();
            bridge.close();
        });
    }

    /** True while Sifu routes the player's keyboard and mouse to Minecraft. */
    public static boolean minecraftOwnsInput() {
        SifuCraftClient client = instance;
        return client != null && client.input.minecraftOwnsInput();
    }

    /** True while a Sifu peer is connected: the real cursor then belongs to Sifu. */
    public static boolean attachedToSifu() {
        SifuCraftClient client = instance;
        return client != null && client.bridge.peerConnected();
    }

    /** Active only for an opted-in, live, anchored Minecraft-owned frame. */
    public static boolean overlayActive() {
        SifuCraftClient client = instance;
        return client != null && client.overlay.enabled() && client.overlayScope() != null;
    }

    public static void captureOverlay() {
        SifuCraftClient client = instance;
        if (client != null && client.overlay.enabled()) client.overlay.render(client.minecraft, client.overlayScope());
    }

    public static void beginGuiFrame() { if (instance != null) instance.crosshair = false; }
    public static void markCrosshair() { if (instance != null) instance.crosshair = true; }
    static boolean hasCrosshair() { return instance != null && instance.crosshair; }

    private OverlayCapture.Scope overlayScope() {
        SifuFrame frame = bridge.latest();
        if (!bridge.peerConnected() || frame == null || !frame.minecraftOwnsInput() ||
                minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive() ||
                minecraft.gui.screen() != null || minecraft.gui.overlay() != null ||
                !respawn.ready() || !space.anchoredTo(frame)) return null;
        return new OverlayCapture.Scope(bridge.session(), bridge.peerSession(), frame.worldGeneration(), frame.anchorEpoch());
    }

    /** Called by {@code SDLEventHandlerMixin} on the render thread after Minecraft polled its input. */
    public static void onFrame() {
        SifuCraftClient client = instance;
        if (client != null) client.frame();
    }

    private void frame() {
        long now = System.currentTimeMillis();
        minecraft.options.pauseOnLostFocus = false;
        hideWindowOnce();
        enterDevWorldOnce();
        if (minecraft.level == null) {
            slots.close();
            space.reset();
        }
        SifuFrame previous = bridge.latest();
        collision.update(bridge.collisionManifest());
        bridge.setLoadedCollision(collision.loadedGeneration());
        respawnIfDead(now, previous);
        closeInvisibleScreens();
        placeIfNeeded(previous);
        publishCollision();
        publishBlocks(previous);
        enforceNoFlight();
        rescueFromFall(previous);
        bridge.tick(now, currentState(previous));
        SifuFrame frame = bridge.latest();
        publishEnemies(frame);
        CombatHits.pump(bridge);
        PlayerDamage.pump(bridge, minecraft.getSingleplayerServer(), minecraft.player == null ? null : minecraft.player.getUUID(),
                minecraft.player, respawn.ready() && minecraft.player != null && minecraft.player.isAlive());
        if (frame == null) space.reset();
        input.setOwner(frame != null && frame.minecraftOwnsInput() && respawn.ready());
        input.applyMotion(frame);
        overlay.updateViewport(minecraft);
        if (now >= nextStatusMs) {
            nextStatusMs = now + STATUS_EVERY_MS;
            logStatus(frame);
        }
    }

    /**
     * For each hand-over from Sifu (anchor epoch), puts Minecraft's player where the pawn is: a
     * server-side teleport to the pawn's feet in its map's place, facing the way it faces, standing
     * on the collision grid's floor there (Unreal characters hover a little, and the grid rounds
     * floors to its cells). The space counts as anchored only once the client has landed, so Sifu
     * never takes a pose from before the teleport.
     */
    private void placeIfNeeded(SifuFrame frame) {
        Player player = minecraft.player;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (frame == null || !frame.hasPawn() || frame.anchorEpoch() == 0 || player == null || server == null ||
                !player.isAlive() || !respawn.mayPlace()
                || space.anchoredTo(frame)) {
            return;
        }
        slots.open(server.getWorldPath(LevelResource.ROOT));
        MapPlacement placement = slots.placement(UnrealSpace.mapName(frame.worldPath()));
        long now = System.currentTimeMillis();
        boolean sameHandOver = teleportEpoch == frame.anchorEpoch() && teleportWorld == frame.worldGeneration();
        if (!sameHandOver || now - teleportSentMs > TELEPORT_RETRY_MS) {
            SifuFrame.Vec3 pawn = frame.playerLocation();
            double feetZ = pawn.z() - frame.capsuleHalfHeight();
            double standZ = feetZ;
            CollisionGrid grid = collision.grid();
            if (grid != null && grid.map().equals(placement.map())) {
                double floor = grid.floorUnder(pawn.x(), pawn.y(), feetZ, PLAYER_HALF_WIDTH_CM, STEP_UP_CM, STEP_DOWN_CM);
                if (!Double.isNaN(floor)) standZ = floor;
            }
            Vec3 target = new Vec3(placement.mcX(pawn.x()), placement.mcY(standZ), placement.mcZ(pawn.y()));
            teleport(server, player, target, UnrealSpace.yawFromUnreal(frame.playerRotation().y()), player.getXRot());
            if (DevWorld.enabled()) stockDevHotbar(server, player);
            if (!sameHandOver) {
                LOG.info("[space] epoch {}: teleporting to {} slot x {}: Minecraft ({}, {}, {}) for pawn feet "
                        + "({}, {}, {}) cm, standing at {} cm", frame.anchorEpoch(), placement.map(),
                        placement.originX(), fmt(target.x), fmt(target.y), fmt(target.z), fmt(pawn.x()), fmt(pawn.y()),
                        fmt(feetZ), fmt(standZ));
            }
            teleportEpoch = frame.anchorEpoch();
            teleportWorld = frame.worldGeneration();
            teleportTarget = target;
            teleportSentMs = now;
            return;
        }
        Vec3 at = player.position();
        double dx = at.x - teleportTarget.x, dz = at.z - teleportTarget.z;
        if (dx * dx + dz * dz > LANDED_HORIZONTAL_SQ || Math.abs(at.y - teleportTarget.y) > LANDED_VERTICAL) return;
        space.anchor(frame, placement);
        lastGround = null;
        SifuFrame.Vec3 pawn = frame.playerLocation();
        // The previous-tick rotation matching the current one means no turn interpolated into the first frames.
        LOG.info("[space] epoch {} landed: off by ({}, {}, {}) cm from the pawn's feet; yaw {} (previous tick {}), "
                        + "pitch {} (previous tick {})", frame.anchorEpoch(),
                fmt(placement.ueX(at.x) - pawn.x()), fmt(placement.ueY(at.z) - pawn.y()),
                fmt(placement.ueZ(at.y) - (pawn.z() - frame.capsuleHalfHeight())), fmt(player.getYRot()),
                fmt(player.yRotO), fmt(player.getXRot()), fmt(player.xRotO));
    }

    /** A server-side teleport (the client follows with the server's position packet). */
    private static void teleport(IntegratedServer server, Player player, Vec3 target, float yaw, float pitch) {
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer onServer = server.getPlayerList().getPlayer(id);
            if (onServer != null) {
                onServer.teleportTo(server.overworld(), target.x, target.y, target.z, Set.of(), yaw, pitch, true);
            }
        });
    }

    /**
     * Development: blocks to build with in the empty hotbar slots (the Minecraft window is hidden, so
     * no inventory screen): plain ones, ones whose faces differ, a light one.
     */
    private static void stockDevHotbar(IntegratedServer server, Player player) {
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer onServer = server.getPlayerList().getPlayer(id);
            if (onServer == null) return;
            for (int slot = 0; slot < DEV_HOTBAR.length; slot++) {
                ItemStack current = onServer.getInventory().getItem(slot);
                if (current.isEmpty() || (slot == 8 && current.is(Items.CRAFTING_TABLE))) {
                    onServer.getInventory().setItem(slot, new ItemStack(DEV_HOTBAR[slot], slot == 8 ? 1 : 64));
                }
            }
        });
    }

    /**
     * Shows Sifu the blocks placed in the stretch of this world where the map it shows sits (in that
     * map's Unreal block coordinates, each with its palette entry: how it looks), whenever they, the
     * map or the palette change.
     */
    private void publishBlocks(SifuFrame frame) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (frame == null || !frame.hasPawn() || server == null || !bridge.peerConnected()) return;
        slots.open(server.getWorldPath(LevelResource.ROOT));
        // Sifu's SifuCraft folder holds the collision folder the grid file is in.
        String grid = bridge.collisionManifest().path();
        if (!grid.isEmpty()) {
            Path collisionFolder = Path.of(grid).getParent();
            if (collisionFolder != null && collisionFolder.getParent() != null) {
                appearance.useSifuFolder(collisionFolder.getParent());
            }
        }
        if (appearance.generation() == 0) return; // not before the blocks can be shown as what they are
        String map = UnrealSpace.mapName(frame.worldPath());
        int version = PlacedBlocks.version();
        if (map.equals(publishedBlocksMap) && version == publishedBlocksVersion
                && appearance.generation() == publishedPalette) {
            return;
        }
        MapPlacement placement = slots.placement(map);
        List<PlacedBlocks.Placed> blocks = PlacedBlocks.between(placement.originX() - MapSlots.SPACING / 2,
                placement.originX() + MapSlots.SPACING / 2);
        int[] xyzKind = new int[blocks.size() * 4];
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = blocks.get(i).pos();
            xyzKind[4 * i] = pos.getX() - placement.originX();
            xyzKind[4 * i + 1] = pos.getZ() - placement.originZ();
            xyzKind[4 * i + 2] = pos.getY() - placement.originY();
            xyzKind[4 * i + 3] = appearance.kind(blocks.get(i).state()); // may export textures: palette grows
        }
        int published = bridge.publishBlocks(map, xyzKind, blocks.size(), appearance.generation());
        publishedPalette = appearance.generation();
        if (published < blocks.size()) {
            LOG.warn("[blocks] {} has {} blocks: only the first {} are shown in Sifu", map, blocks.size(), published);
        }
        if (!map.equals(publishedBlocksMap) || published != blockCountLogged) {
            LOG.info("[blocks] {}: {} blocks published to Sifu", map, published);
            blockCountLogged = published;
        }
        publishedBlocksMap = map;
        publishedBlocksVersion = version;
    }

    /** Keep old-life poses out of the bridge until respawn and a fresh Sifu anchor have landed. */
    private void respawnIfDead(long now, SifuFrame frame) {
        var player = minecraft.player;
        boolean dead = player != null && player.isDeadOrDying();
        var action = respawn.tick(now, bridge.peerConnected(), bridge.peerSession(), frame, player != null, dead,
                frame != null && space.anchoredTo(frame));
        if (dead && bridge.peerConnected() && minecraft.gui.screen() instanceof DeathScreen) minecraft.gui.setScreen(null);
        switch (action) {
            case DIED -> {
                space.reset(); teleportEpoch = 0; lastGround = null;
                input.setOwner(false);
                LOG.info("[respawn] Minecraft player died; waiting 1 s, pose and damage suspended");
            }
            case RESPAWN -> {
                if (player != null) player.respawn();
                LOG.info("[respawn] sent vanilla respawn request");
            }
            case REQUEST -> {
                space.reset(); teleportEpoch = 0; lastGround = null;
                LOG.info("[respawn] requesting new anchor after epoch {}", respawn.requestEpoch());
            }
            case ACKNOWLEDGED -> {
                space.reset(); teleportEpoch = 0;
                LOG.info("[respawn] Sifu acknowledged new epoch {}; awaiting landing", frame.anchorEpoch());
            }
            case LANDED -> LOG.info("[respawn] completed at epoch {}; pose, input and damage resumed", frame.anchorEpoch());
            default -> { }
        }
    }

    /**
     * While attached to Sifu the Minecraft window is hidden, so a screen the player opens (inventory,
     * chat, a menu) could never be seen or closed (Esc belongs to Sifu), and it would take the mouse
     * away from the game: it is closed at once. Loading and saving screens are Minecraft's own
     * business; a death is handled by {@link #respawnIfDead}.
     */
    private void closeInvisibleScreens() {
        Screen screen = minecraft.gui.screen();
        if (screen == null || minecraft.player == null || !bridge.peerConnected() || screen instanceof DeathScreen) return;
        String name = screen.getClass().getSimpleName();
        if (name.contains("Loading") || name.contains("Receiving") || name.contains("Message")) return;
        minecraft.gui.setScreen(null);
        if (closedScreens.add(name)) LOG.info("[input] closed {}: Minecraft's window is hidden while attached to Sifu", name);
    }

    /** While attached to Sifu the player walks on Sifu's level: no flying (creative grants it again on every ability sync). */
    private void enforceNoFlight() {
        Player player = minecraft.player;
        if (player == null || !bridge.peerConnected() || !player.getAbilities().mayfly) return;
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
    }

    /**
     * A player that drops far below where it last stood (a hole in the grid, a gap between Sifu's
     * collision and the edge of the level) is put back there instead of falling through the world.
     */
    private void rescueFromFall(SifuFrame frame) {
        Player player = minecraft.player;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (player == null || server == null || frame == null || !space.anchoredTo(frame)) {
            lastGround = null;
            return;
        }
        if (player.onGround()) {
            // Only ground the Sifu grid confirms: standing on nothing (a stale on-ground flag right
            // after a teleport, the flat ground under the level) must not become the place to go back to.
            SifuCollision.Active active = SifuCollision.active();
            Vec3 at = player.position();
            if (active != null && !Double.isNaN(active.grid().floorUnder(active.placement().ueX(at.x),
                    active.placement().ueY(at.z), active.placement().ueZ(at.y), PLAYER_HALF_WIDTH_CM, 15.0, 15.0))) {
                lastGround = at;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (lastGround == null || player.getY() > lastGround.y - FALL_RESCUE_BLOCKS || now - rescueSentMs < RESCUE_COOLDOWN_MS) {
            return;
        }
        rescueSentMs = now;
        teleport(server, player, lastGround, player.getYRot(), player.getXRot());
        LOG.info("[space] fell {} blocks below the last ground: put back at ({}, {}, {})",
                fmt(lastGround.y - player.getY()), fmt(lastGround.x), fmt(lastGround.y), fmt(lastGround.z));
    }

    /** Hands the loaded grid and its map's place to collision queries (client and server). */
    private void publishCollision() {
        CollisionGrid grid = collision.grid();
        SifuCollision.Active current = SifuCollision.active();
        if (grid == null || !slots.isOpen()) {
            if (current != null) SifuCollision.set(null);
            return;
        }
        if (current != null && current.grid() == grid) return;
        MapPlacement placement = slots.placement(grid.map());
        SifuCollision.set(new SifuCollision.Active(grid, placement));
        LOG.info("[collision] in force: {} ({} blocks) at Minecraft origin ({}, {}, {})", grid.map(), grid.blockCount(),
                placement.originX(), placement.originY(), placement.originZ());
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    private void publishEnemies(SifuFrame frame) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || frame == null || !frame.hasPawn()) {
            EnemyMirrors.offer(null, null, null, false);
            return;
        }
        slots.open(server.getWorldPath(LevelResource.ROOT));
        EnemyMirrors.offer(frame, bridge.enemies(), slots.placement(UnrealSpace.mapName(frame.worldPath())),
                respawn.ready() && minecraft.player != null && minecraft.player.isAlive() && space.anchoredTo(frame));
    }

    private McState currentState(SifuFrame frame) {
        if (!respawn.ready()) return McState.withoutPose(respawn.flags(), respawn.requestEpoch(),
                bridge.peerSession(), frame == null ? 0 : frame.worldGeneration());
        Player player = minecraft.player;
        if (minecraft.level == null || player == null) return McState.NOT_READY;
        if (frame == null || !space.anchoredTo(frame)) return McState.ready();

        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 feet = player.getPosition(partialTick);
        Vec3 motion = player.getDeltaMovement();
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 eye = camera.isInitialized() ? camera.position() : feet.add(0, player.getEyeHeight(), 0);
        float cameraPitch = camera.isInitialized() ? camera.xRot() : player.getXRot();
        float cameraYaw = camera.isInitialized() ? camera.yRot() : player.getYRot();

        int flags = Protocol.MC_READY | Protocol.MC_HAS_POSE;
        if (player.onGround()) flags |= Protocol.MC_ON_GROUND;
        if (player.isCrouching()) flags |= Protocol.MC_SNEAKING;
        if (player.isSprinting()) flags |= Protocol.MC_SPRINTING;
        if (player.getAbilities().flying) flags |= Protocol.MC_FLYING;
        return new McState(flags, perspective(minecraft.options.getCameraType()),
                space.toUnreal(feet.x, feet.y, feet.z),
                UnrealSpace.rotationToUnreal(0f, player.getYRot(partialTick)),
                UnrealSpace.velocityToUnreal(motion.x, motion.y, motion.z),
                space.toUnreal(eye.x, eye.y, eye.z),
                UnrealSpace.rotationToUnreal(cameraPitch, cameraYaw),
                minecraft.options.fov().get(),
                space.anchorEpoch(), bridge.peerSession(), frame.worldGeneration());
    }

    private static int perspective(CameraType type) {
        if (type == CameraType.THIRD_PERSON_BACK) return 1;
        if (type == CameraType.THIRD_PERSON_FRONT) return 2;
        return 0;
    }

    /** Once loading is done and some menu (title, onboarding, ...) is up, go straight into the world. */
    private void enterDevWorldOnce() {
        if (devWorldStarted || !DevWorld.enabled() || minecraft.level != null) return;
        if (minecraft.gui.overlay() != null || minecraft.gui.screen() == null) return;
        devWorldStarted = true;
        if (minecraft.options.onboardAccessibility) minecraft.options.onboardingAccessibilityFinished();
        LOG.info("[dev] entering world '{}' from {}", DevWorld.LEVEL_ID, minecraft.gui.screen().getClass().getSimpleName());
        DevWorld.enter(minecraft);
    }

    private void hideWindowOnce() {
        if (windowHidden || !Boolean.getBoolean("sifucraft.hideWindow")) return;
        windowHidden = true;
        SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
        LOG.info("[dev] Minecraft window hidden");
    }

    private void logInput(String kind, int code, int action, int modifiers) {
        if (loggedInput++ >= MAX_LOGGED_INPUT) return;
        LOG.info("[input] {} {} action={} mods=0x{}", kind, code, action, Integer.toHexString(modifiers));
    }

    private void logStatus(SifuFrame frame) {
        InputInjector.Stats s = input.stats();
        Player player = minecraft.player;
        String screen = minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName();
        String where = player == null ? "no player, screen=" + screen
                : String.format("pos=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f ground=%s crouch=%s screen=%s paused=%s",
                        player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                        player.onGround(), player.isShiftKeyDown(), screen, minecraft.isPaused());
        LOG.info("[status] sifu={} owner={} {} | injected keys={} buttons={} moves={} dx={} dy={} scroll={} releaseAll={}",
                bridge.peerConnected() ? "connected" : "absent", input.minecraftOwnsInput() ? "MINECRAFT" : "sifu",
                where, s.keys, s.buttons, s.moves, Math.round(s.dxSum), Math.round(s.dySum), s.scrollSum,
                s.releaseAlls);
        if (minecraft.level != null && bridge.enemies() != null && frame != null && frame.hasPawn() && slots.isOpen()) {
            int count = 0;
            int sizeMismatches = 0;
            double maxError = 0;
            MapPlacement placement = slots.placement(UnrealSpace.mapName(frame.worldPath()));
            for (var entity : minecraft.level.entitiesForRendering()) {
                if (!(entity instanceof EnemyProxy proxy)) continue;
                count++;
                for (var enemy : bridge.enemies().entries()) {
                    if (!enemy.key().equals(proxy.sifuKey())) continue;
                    if (Math.abs(proxy.getBbWidth() - enemy.radius() * 2 / 100.0f) > 0.001f ||
                            Math.abs(proxy.getBbHeight() - enemy.halfHeight() * 2 / 100.0f) > 0.001f) sizeMismatches++;
                    double x = placement.mcX(enemy.location().x());
                    double y = placement.mcY(enemy.location().z() - enemy.halfHeight());
                    double z = placement.mcZ(enemy.location().y());
                    double dx = x - proxy.getX(), dy = y - proxy.getY(), dz = z - proxy.getZ();
                    maxError = Math.max(maxError, Math.sqrt(dx * dx + dy * dy + dz * dz) * 100);
                }
            }
            LOG.info("[combat] client proxies={} table={} world={} max_position_error_cm={} size_mismatches={}", count,
                    bridge.enemies().entries().size(), bridge.enemies().worldGeneration(), fmt(maxError), sizeMismatches);
        }
    }
}
