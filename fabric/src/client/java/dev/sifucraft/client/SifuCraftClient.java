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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: attaches the bridge as the Minecraft endpoint and, every frame right after
 * Minecraft polls its own input, applies the input Sifu routes here.
 *
 * <p>Development system properties: {@code sifucraft.devWorld} enters a superflat development world as
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

    private static volatile SifuCraftClient instance;

    private Minecraft minecraft;
    private McBridge bridge;
    private InputInjector input;
    private InventoryCheck inventoryCheck;
    private DevItemCheck devItemCheck;
    private dev.sifucraft.combat.TotemCheck totemCheck;
    private FoodCheck foodCheck;
    private MaceCheck maceCheck;
    private WindChargeCheck windChargeCheck;
    private ElytraCheck elytraCheck;
    private NativeInteractionCheck interactionCheck;
    private WorldEffectsCheck worldEffectsCheck;
    private WorldCracksCheck worldCracksCheck;
    private WorldModelCheck worldModelCheck;
    private WorldAudioCheck worldAudioCheck;
    private AvatarCheck avatarCheck;
    private WorldStressCheck worldStressCheck;
    private final NativeUse nativeUse = new NativeUse();
    public static void beginUseGesture() { if (instance != null) instance.nativeUse.press(); }
    public static boolean handleNativeUse() { return instance != null && instance.nativeUse.handle(instance.bridge, instance.minecraft); }
    private int guiWireEvents;
    private final OverlayCapture overlay = new OverlayCapture();
    private final WorldLayerCapture worldLayers = new WorldLayerCapture();
    private final BlockShapes blockShapes = new BlockShapes();
    private BlockModelCheck blockModelCheck;
    private FluidRenderCheck fluidRenderCheck;
    private PistonStressCheck pistonStressCheck;
    private SignalCheck signalCheck;
    private PlayerSignalCheck playerSignalCheck;
    private final HostLanguage hostLanguage = new HostLanguage();
    private boolean crosshair;
    private CollisionLoader collision;
    private CollisionLoader doors;
    private final UnrealSpace space = new UnrealSpace();
    private final RespawnHandshake respawn = new RespawnHandshake();
    private final dev.sifucraft.combat.ShieldBlockAnimation shieldAnimation = new dev.sifucraft.combat.ShieldBlockAnimation();
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
        EntityRenderers.register(dev.sifucraft.blocks.SignalEntities.PLAYER_BODY, NoopRenderer::new);
        minecraft = Minecraft.getInstance();
        input = new InputInjector(minecraft);
        bridge = new McBridge(new McBridge.Listener() {
            @Override public void onNativeInteraction(dev.sifucraft.bridge.NativeInteraction.Result result) {
                LOG.info("[interaction] native reply request={} outcome={} target={} world={} epoch={}",
                        result.request(), result.outcome(), result.target(), result.world(), result.epoch());
            }
            @Override public void onDamageResult(DamageResult result) { CombatHits.result(result); }
            @Override public void onPlayerHit(long session, long eventId, PlayerHit hit) {
                var player = minecraft.player;
                boolean shield = player != null && player.isUsingItem() &&
                        player.getUseItem().has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS);
                PlayerDamage.receive(session, eventId, hit, shield ? player.getUsedItemHand() : null,
                        shield ? player.getUseItem().copy() : net.minecraft.world.item.ItemStack.EMPTY);
            }
            @Override
            public void log(String message) {
                LOG.info("[bridge] {}", message);
            }

            @Override
            public void onKey(int scancode, int action, int modifiers) {
                logInput("key", scancode, action, modifiers);
                input.key(scancode, action, modifiers);
            }
            @Override public void onKey(int scancode, int action, int modifiers, int guiRevision) {
                logInput("key", scancode, action, modifiers);
                input.key(scancode, action, modifiers, guiRevision);
            }
            @Override public void onGuiMotion(double x, double y, double scroll) { guiWireEvents++; input.guiMotion(x, y, scroll); }

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
        doors = new CollisionLoader(message -> LOG.info("[doors] {}", message));
        appearance = new BlockAppearance(minecraft, message -> LOG.info("[blocks] {}", message));
        instance = this;
        if (Boolean.getBoolean("sifucraft.survivalDifficultySelfTest")) {
            ServerLifecycleEvents.SERVER_STARTED.register(SurvivalDifficultyCheck::prepare);
            var check = new SurvivalDifficultyCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
        }
        ServerLifecycleEvents.SERVER_STARTED.register(DevWorld::migrateDifficulty);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> PlacedBlocks.open(server.getWorldPath(LevelResource.ROOT),
                message -> LOG.info("[blocks] {}", message)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PlacedBlocks.close());
        ServerTickEvents.END_SERVER_TICK.register(server -> PlacedBlocks.saveIfDirty());
        var sceneSupportUpdates=new dev.sifucraft.blocks.SceneBlockSupport.Updates();
        ServerTickEvents.END_SERVER_TICK.register(sceneSupportUpdates::tick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> NewPlayerSetup.reset());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> NewPlayerSetup.joined(handler.player));
        if (Boolean.getBoolean("sifucraft.inventorySelfTest")) {
            inventoryCheck = new InventoryCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(inventoryCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.combatSelfTest")) {
            CombatCheck check = new CombatCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
        }
        if (Boolean.getBoolean("sifucraft.shieldSelfTest")) {
            var check = new dev.sifucraft.combat.ShieldCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(check::restore);
        }
        if (Boolean.getBoolean("sifucraft.devItemsSelfTest")) devItemCheck = new DevItemCheck();
        if (Boolean.getBoolean("sifucraft.totemSelfTest")) {
            totemCheck = new dev.sifucraft.combat.TotemCheck();
            ServerTickEvents.END_SERVER_TICK.register(totemCheck::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(totemCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.foodSelfTest")) {
            foodCheck = new FoodCheck();
            ServerTickEvents.END_SERVER_TICK.register(foodCheck::serverTick);
            ServerLifecycleEvents.SERVER_STOPPING.register(foodCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.maceSelfTest")) {
            maceCheck = new MaceCheck();
            ServerTickEvents.END_SERVER_TICK.register(maceCheck::serverTick);
            ServerLifecycleEvents.SERVER_STOPPING.register(maceCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.windChargeSelfTest")) {
            windChargeCheck = new WindChargeCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(windChargeCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.elytraSelfTest")) {
            elytraCheck = new ElytraCheck();
            ServerTickEvents.END_SERVER_TICK.register(elytraCheck::serverTick);
            ServerLifecycleEvents.SERVER_STOPPING.register(elytraCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.nativeInteractionSelfTest")) {
            interactionCheck = new NativeInteractionCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(interactionCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.worldEffectsSelfTest")) {
            worldEffectsCheck = new WorldEffectsCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(worldEffectsCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.worldCracksSelfTest")) {
            worldCracksCheck = new WorldCracksCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(worldCracksCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.worldModelSelfTest")) {
            worldModelCheck = new WorldModelCheck();
            ServerTickEvents.END_SERVER_TICK.register(worldModelCheck::serverTick);
            ServerLifecycleEvents.SERVER_STOPPING.register(worldModelCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.worldAudioSelfTest")) worldAudioCheck = new WorldAudioCheck(minecraft);
        if (Boolean.getBoolean("sifucraft.avatarSelfTest")) {
            avatarCheck = new AvatarCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(avatarCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.worldStressSelfTest")) {
            worldStressCheck = new WorldStressCheck();
            ServerTickEvents.END_SERVER_TICK.register(worldStressCheck::serverTick);
            ServerLifecycleEvents.SERVER_STOPPING.register(worldStressCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.redstoneSelfTest")) {
            var check = new RedstoneCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(check::restore);
        }
        if(Boolean.getBoolean("sifucraft.signalSelfTest")){
            signalCheck=new SignalCheck();
            ServerTickEvents.END_SERVER_TICK.register(signalCheck::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(signalCheck::restore);
        }
        if(Boolean.getBoolean("sifucraft.playerSignalSelfTest")){
            playerSignalCheck=new PlayerSignalCheck();
            ServerTickEvents.END_SERVER_TICK.register(playerSignalCheck::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(playerSignalCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.blockModelSelfTest")) {
            blockModelCheck = new BlockModelCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(blockModelCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.fluidRenderSelfTest")) {
            fluidRenderCheck = new FluidRenderCheck();
            ServerLifecycleEvents.SERVER_STOPPING.register(fluidRenderCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.pistonStressSelfTest")) {
            pistonStressCheck = new PistonStressCheck();
            ServerTickEvents.END_SERVER_TICK.register(pistonStressCheck::serverTick);
            ServerLifecycleEvents.SERVER_STOPPING.register(pistonStressCheck::restore);
        }
        if (Boolean.getBoolean("sifucraft.sceneSupportSelfTest")) {
            var check=new BlockSupportCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
            ServerLifecycleEvents.SERVER_STOPPING.register(check::restore);
        }
        if (Boolean.getBoolean("sifucraft.playerDamageTest") || Boolean.getBoolean("sifucraft.playerHitSelfTest") ||
                Boolean.getBoolean("sifucraft.respawnSelfTest")) {
            PlayerHitCheck check = new PlayerHitCheck();
            ServerTickEvents.END_SERVER_TICK.register(check::tick);
            ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> check.disconnect(server, handler.player));
            ServerLifecycleEvents.SERVER_STOPPING.register(check::restore);
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (blockModelCheck != null) blockModelCheck.restoreClient(client);
            if (fluidRenderCheck != null) fluidRenderCheck.restoreClient(client);
            if (worldStressCheck != null) worldStressCheck.restoreClient(client);
            if (avatarCheck != null) avatarCheck.restoreClient(client);
            if (worldAudioCheck != null) worldAudioCheck.close();
            overlay.close();
            worldLayers.close();
            blockShapes.close();
            collision.close();
            doors.close();
            bridge.close();
        });
    }

    /** True while Sifu routes the player's keyboard and mouse to Minecraft. */
    public static boolean minecraftOwnsInput() {
        SifuCraftClient client = instance;
        return client != null && client.input.minecraftOwnsInput();
    }

    public static boolean devItemsAvailable() {
        var client = instance;
        return Boolean.getBoolean("sifucraft.devItems") && DevWorld.enabled() && client != null &&
                client.minecraft.getSingleplayerServer() != null && client.guiAvailable(client.bridge.latest());
    }
    public static boolean devItemsCheckComplete() { return !Boolean.getBoolean("sifucraft.devItemsSelfTest") || DevItemCheck.complete; }

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
        if (client != null && client.overlay.enabled()) {
            var scope = client.overlayScope();
            var world = client.worldScope();
            client.worldLayers.updateScope(world);
            client.overlay.render(client.minecraft, scope, world);
        }
    }
    public static void captureWorld(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        var client = instance;
        if (client != null && client.bridge.latest() != null)
            client.worldLayers.finish(client.minecraft, client.worldScope(), camera, client.space, client.bridge.latest().frame());
    }
    public static void worldProjection(org.joml.Matrix4fc projection) {
        if (instance != null) instance.worldLayers.projection(projection);
    }
    static boolean worldLayersActive() { return instance != null && instance.worldLayers.enabled() && WorldEffects.active(); }
    static boolean blockWorldAttached() {
        var c = instance;
        return c != null && WorldEffects.configured() && c.worldLayers.enabled() &&
                c.bridge.peerConnected() && c.minecraft.level != null;
    }
    public static boolean blockModelsActive() { return instance != null && instance.blockShapes.active(); }
    static boolean blockCollisionCurrent() { return instance != null && instance.blockShapes.caughtUp(); }
    public static boolean worldEffectsAvailable() {
        var c = instance;
        return c != null && (overlayActive() || (c.worldLayers.enabled() && c.worldScope() != null));
    }
    public static dev.sifucraft.bridge.WorldView nativeWorldView() {
        var c = instance;
        if (c == null || !c.worldLayers.enabled() || !WorldEffects.active() || c.worldScope() == null) return null;
        var frame = c.bridge.latest();
        if (frame.minecraftOwnsInput()) return null;
        return dev.sifucraft.bridge.WorldView.from(frame, c.space.placement(),
                c.minecraft.getWindow().getWidth(), c.minecraft.getWindow().getHeight());
    }

    public static void beginGuiFrame() { if (instance != null) instance.crosshair = false; }
    public static void markCrosshair() { if (instance != null) instance.crosshair = true; }
    static boolean hasCrosshair() { return instance != null && instance.crosshair; }
    static int guiWireEvents() { return instance == null ? 0 : instance.guiWireEvents; }
    public static void drawPointer(net.minecraft.client.gui.GuiGraphicsExtractor gui) {
        if (mirroredScreenActive()) SmoothPointer.draw(instance.minecraft, gui);
    }
    public static void drawNativeInteraction(net.minecraft.client.gui.GuiGraphicsExtractor gui) {
        var c = instance;
        if (c == null || !overlayActive() || c.minecraft.gui.screen() != null || c.minecraft.options.keyShift.isDown() ||
                c.nativeUse.candidate(c.bridge, c.minecraft) == null) return;
        var text = net.minecraft.network.chat.Component.literal("[").append(c.minecraft.options.keyUse.getTranslatedKeyMessage())
                .append("] ").append(net.minecraft.network.chat.Component.translatable("sifucraft.native_interact"));
        int x = (gui.guiWidth() - c.minecraft.font.width(text)) / 2;
        gui.text(c.minecraft.font, text, x, gui.guiHeight() / 2 + 20, 0xFFFFFFFF);
    }
    public static boolean mirroredScreenActive() {
        var client = instance;
        return client != null && client.input.minecraftOwnsInput() && client.guiAvailable(client.bridge.latest()) &&
                client.minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
    }
    static int cursorReferenceWidth() {
        return instance.overlay.viewportWidth() > 0 ? instance.overlay.viewportWidth() : instance.minecraft.getWindow().getWidth();
    }
    static int cursorReferenceHeight() {
        return instance.overlay.viewportHeight() > 0 ? instance.overlay.viewportHeight() : instance.minecraft.getWindow().getHeight();
    }
    private boolean guiAvailable(SifuFrame frame) {
        return overlay.enabled() && bridge.peerConnected() && frame != null && frame.minecraftOwnsInput() &&
                (frame.flags() & Protocol.SNAPSHOT_GUI_AVAILABLE) != 0 && respawn.ready() && space.anchoredTo(frame) &&
                minecraft.player != null && minecraft.player.isAlive() && minecraft.gui.overlay() == null;
    }

    private OverlayCapture.Scope overlayScope() {
        SifuFrame frame = bridge.latest();
        if (!bridge.peerConnected() || frame == null || !frame.minecraftOwnsInput() ||
                minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive() ||
                (minecraft.gui.screen() != null && !mirroredScreenActive()) || minecraft.gui.overlay() != null ||
                !respawn.ready() || !space.anchoredTo(frame)) return null;
        return new OverlayCapture.Scope(bridge.session(), bridge.peerSession(), frame.worldGeneration(), frame.anchorEpoch());
    }
    private OverlayCapture.Scope worldScope() {
        SifuFrame frame = bridge.latest();
        if (!bridge.peerConnected() || frame == null || !frame.hasPawn() || !frame.hasCamera() ||
                minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive() ||
                minecraft.gui.screen() != null && !mirroredScreenActive() || minecraft.gui.overlay() != null ||
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
        nativeUse.update(bridge, minecraft);
        collision.update(bridge.collisionManifest());
        doors.update(previous != null && bridge.doors().worldGeneration() == previous.worldGeneration()
                ? bridge.doors() : dev.sifucraft.bridge.CollisionManifest.NONE);
        bridge.setLoadedCollision(collision.loadedGeneration());
        respawnIfDead(now, previous);
        closeInvisibleScreens();
        input.syncMouseMode();
        placeIfNeeded(previous);
        publishCollision();
        blockShapes.update(minecraft, bridge, space.placement());
        WorldTerrain.update(minecraft);
        publishBlocks(previous);
        enforceNoFlight();
        rescueFromFall(previous);
        var outgoing=currentState(previous);
        if(playerSignalCheck!=null)outgoing=playerSignalCheck.protectNativePose(outgoing,guiAvailable(previous));
        bridge.tick(now, outgoing);
        bridge.setUseBinding(input.useBinding());
        SifuFrame frame = bridge.latest();
        DevItemAccess.update(minecraft, bridge.peerSession(), frame == null ? 0 : frame.worldGeneration(),
                frame == null ? 0 : frame.anchorEpoch(), frame != null && guiAvailable(frame));
        if (bridge.peerConnected() && frame != null) hostLanguage.update(minecraft, frame.language());
        publishEnemies(frame);
        publishPlayerSignals(frame);
        CombatHits.pump(bridge);
        dev.sifucraft.combat.ShieldBlocks.pump(bridge);
        PlayerDamage.pump(bridge, minecraft.getSingleplayerServer(), minecraft.player == null ? null : minecraft.player.getUUID(),
                minecraft.player, respawn.ready() && minecraft.player != null && minecraft.player.isAlive());
        shieldAnimation.pump(minecraft, bridge, frame != null && respawn.ready() && space.anchoredTo(frame));
        if (frame == null) space.reset();
        input.setOwner(frame != null && frame.minecraftOwnsInput() && respawn.ready());
        input.applyMotion(frame);
        overlay.updateViewport(minecraft);
        if (inventoryCheck != null) inventoryCheck.tick(minecraft, input, guiAvailable(frame));
        if (devItemCheck != null) devItemCheck.tick(minecraft, input, guiAvailable(frame));
        if (totemCheck != null) totemCheck.clientTick(minecraft);
        if (foodCheck != null) foodCheck.tick(minecraft, input, guiAvailable(frame));
        if (maceCheck != null) maceCheck.clientTick(minecraft, guiAvailable(frame));
        if (windChargeCheck != null) windChargeCheck.tick(minecraft, input, guiAvailable(frame));
        if (elytraCheck != null) elytraCheck.tick(minecraft, input, guiAvailable(frame));
        if (interactionCheck != null) interactionCheck.tick(minecraft, input, guiAvailable(frame));
        if (worldEffectsCheck != null) worldEffectsCheck.tick(minecraft, input, guiAvailable(frame));
        if (blockModelCheck != null) blockModelCheck.tick(minecraft,guiAvailable(frame));
        if (fluidRenderCheck != null) fluidRenderCheck.tick(minecraft,guiAvailable(frame));
        if (pistonStressCheck != null) pistonStressCheck.clientTick(minecraft,guiAvailable(frame));
        if (signalCheck != null) signalCheck.clientTick(minecraft,guiAvailable(frame));
        if (playerSignalCheck != null) playerSignalCheck.clientTick(minecraft,guiAvailable(frame),frame);
        if (worldCracksCheck != null) worldCracksCheck.tick(minecraft, input, guiAvailable(frame));
        if (worldModelCheck != null) worldModelCheck.tick(minecraft, guiAvailable(frame));
        if (avatarCheck != null) avatarCheck.tick(minecraft, input, guiAvailable(frame));
        if (worldStressCheck != null) worldStressCheck.tick(minecraft, guiAvailable(frame));
        if (worldAudioCheck != null) worldAudioCheck.tick(frame, worldScope() != null);
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
        if (guiAvailable(bridge.latest()) && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) return;
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) minecraft.player.closeContainer();
        else minecraft.gui.setScreen(null);
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
        var dynamic = doors.grid();
        if (dynamic != null && !dynamic.map().equals(grid.map())) dynamic = null;
        long session=bridge.peerSession();int world=bridge.collisionManifest().worldGeneration();
        if (current != null && current.grid() == grid && current.doors() == dynamic && current.matchesScope(session,world)) return;
        MapPlacement placement = slots.placement(grid.map());
        SifuCollision.set(new SifuCollision.Active(grid, placement, dynamic,session,world));
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

    private void publishPlayerSignals(SifuFrame frame) {
        if(playerSignalCheck!=null)return;
        var server=minecraft.getSingleplayerServer();
        if(!bridge.peerConnected()||server==null||minecraft.player==null||frame==null||!frame.hasPawn()){
            dev.sifucraft.blocks.PlayerSignalContacts.offer(null,0,null,null,null,false);return;
        }
        slots.open(server.getWorldPath(LevelResource.ROOT));
        dev.sifucraft.blocks.PlayerSignalContacts.offer(server,bridge.peerSession(),frame,
            slots.placement(UnrealSpace.mapName(frame.worldPath())),minecraft.player.getUUID(),
            respawn.ready()&&minecraft.player.isAlive()&&space.anchoredTo(frame));
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
                space.anchorEpoch(), bridge.peerSession(), frame.worldGeneration()).withGui(
                    overlay.enabled() && frame.minecraftOwnsInput() && minecraft.gui.overlay() == null,
                    mirroredScreenActive(), input.guiInputAck(), minecraft.gui.screen() != null &&
                        minecraft.gui.screen().getFocused() instanceof net.minecraft.client.gui.components.EditBox);
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
