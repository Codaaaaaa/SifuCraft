package dev.sifucraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Vanilla crack geometry/textures adapted to the transparent color/device-depth world layer. */
public final class WorldCracks {
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();
    private static final RenderPipeline PIPELINE = pipeline();
    private WorldCracks() {}
    private static RenderPipeline pipeline() {
        var vanilla = RenderPipelines.CRUMBLING;
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("sifucraft", "pipeline/world_cracks"))
                .withVertexShader(vanilla.getShaders().get(ShaderType.VERTEX))
                .withFragmentShader(Identifier.fromNamespaceAndPath("sifucraft", "core/world_cracks"))
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .withPrimitiveTopology(vanilla.getPrimitiveTopology()).withCull(vanilla.isCull())
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                // Vanilla decals do not write depth; the bridge needs the target surface for reprojection.
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 1, 10));
        vanilla.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        return builder.build();
    }
    public static RenderType renderType(int stage) {
        var texture = Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png");
        return TYPES.computeIfAbsent(texture, id -> RenderType.create("sifucraft_world_cracks",
                RenderSetup.builder(PIPELINE).withTexture("Sampler0", id).sortOnUpload().createRenderSetup()));
    }
}
