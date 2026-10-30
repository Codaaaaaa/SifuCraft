package dev.sifucraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sifucraft.combat.EnemyProxy;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The proxy body stays invisible; only a head-slot item is drawn, on the Sifu enemy's head bone. */
public final class EnemyProxyRenderer extends EntityRenderer<EnemyProxy, EnemyProxyRenderer.State> {
    // Head bone origin to skull centre, and item size (vanilla's 0.625 on a 0.5-block head), in blocks.
    private static final float LIFT = Float.parseFloat(System.getProperty("sifucraft.headItemLiftCm", "10")) / 100;
    private static final float SCALE = Float.parseFloat(System.getProperty("sifucraft.headItemScale", "0.3"));

    public static final class State extends EntityRenderState {
        public final ItemStackRenderState head = new ItemStackRenderState();
        final Vector3f offset = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
    }

    private final ItemModelResolver items;

    public EnemyProxyRenderer(EntityRendererProvider.Context context) {
        super(context);
        items = context.getItemModelResolver();
    }

    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(EnemyProxy proxy, State state, float partialTicks) {
        super.extractRenderState(proxy, state, partialTicks);
        state.head.clear();
        var stack = proxy.getItemBySlot(EquipmentSlot.HEAD);
        var head = stack.isEmpty() ? null : SifuCraftClient.enemyHead(proxy.sifuKey());
        var placement = SifuCraftClient.worldPlacement();
        if (head == null || placement == null) return;
        // Unreal (x, y, z) is MC (x, z, y).
        var up = new Vector3f(head.up().x(), head.up().z(), head.up().y()).normalize();
        var forward = new Vector3f(head.forward().x(), head.forward().z(), head.forward().y());
        forward.sub(new Vector3f(up).mul(forward.dot(up))).normalize();
        if (!forward.isFinite() || !up.isFinite()) return;
        // Vanilla head items face local -Z with local +X = forward x up.
        var basis = new Matrix3f().setColumn(0, new Vector3f(forward).cross(up)).setColumn(1, up)
                .setColumn(2, new Vector3f(forward).negate());
        state.rotation.setFromNormalized(basis);
        var at = head.location();
        state.offset.set((float) (placement.mcX(at.x()) - state.x), (float) (placement.mcY(at.z()) - state.y),
                (float) (placement.mcZ(at.y()) - state.z)).add(up.mul(LIFT));
        items.updateForLiving(state.head, stack, ItemDisplayContext.HEAD, proxy);
    }

    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.head.isEmpty()) return;
        pose.pushPose();
        pose.translate(state.offset.x, state.offset.y, state.offset.z);
        pose.rotate(state.rotation);
        pose.scale(SCALE, SCALE, SCALE);
        state.head.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose();
    }
}
