package dev.sifucraft.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How the blocks look in Sifu: each block state gets a palette entry naming the texture of each of
 * its six faces, exported from Minecraft's own resources as PNGs into Sifu's SifuCraft\textures
 * folder, listed in palette.txt there ("kind +X -X +Y -Y +Z -Z", Unreal axes). A face is composited
 * from the block model's quads on that side (an overlay over its base, tinted where Minecraft tints
 * it, e.g. grass), animated textures keep their first frame, and pixels are scaled up so Sifu's
 * texture filtering keeps them sharp. Render thread.
 */
final class BlockAppearance {
    private static final int SCALE = 16;
    private static final String PALETTE = "palette.txt";
    private static final long MODEL_SEED = 42L;
    // Unreal +X, -X, +Y, -Y, +Z, -Z as Minecraft sides (Minecraft x = Unreal x, z = Unreal y, y = Unreal z).
    private static final Direction[] FACES = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH,
            Direction.UP, Direction.DOWN};

    private final Minecraft minecraft;
    private final Consumer<String> log;
    private final Map<BlockState, Integer> kinds = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final Set<String> exported = new HashSet<>();
    private Path folder;
    private int generation;

    BlockAppearance(Minecraft minecraft, Consumer<String> log) {
        this.minecraft = minecraft;
        this.log = log;
    }

    /** Uses {@code sifuCraft}\textures (Sifu's folder); a new folder starts a new palette. */
    void useSifuFolder(Path sifuCraft) {
        Path wanted = sifuCraft.resolve("textures");
        if (wanted.equals(folder)) return;
        try {
            Files.createDirectories(wanted);
        } catch (IOException e) {
            log.accept("cannot create " + wanted + ": " + e);
            return;
        }
        folder = wanted;
        kinds.clear();
        palette.clear();
        exported.clear();
        generation++;
        writePalette();
        log.accept("block textures go to " + folder);
    }

    /** Bumped with every palette change; 0 until Sifu's folder is known. */
    int generation() {
        return folder == null ? 0 : generation;
    }

    /** The palette entry of {@code state}, its textures exported the first time; 0 if there is none. */
    int kind(BlockState state) {
        if (folder == null) return 0;
        Integer known = kinds.get(state);
        if (known != null) return known;
        int kind = 0;
        try {
            BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(MODEL_SEED), parts);
            String[] faces = new String[FACES.length];
            for (int f = 0; f < FACES.length; f++) {
                faces[f] = exportFace(state, model, parts, FACES[f]);
            }
            kind = palette.size() + 1;
            palette.add(kind + " " + String.join(" ", faces));
            generation++;
            writePalette();
        } catch (IOException | RuntimeException e) {
            log.accept("cannot export the textures of " + state + ": " + e);
        }
        kinds.put(state, kind);
        return kind;
    }

    /** The texture of one side: the model's quads there composited in order, as a file name. */
    private String exportFace(BlockState state, BlockStateModel model, List<BlockStateModelPart> parts, Direction side)
            throws IOException {
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(side)) {
                BakedQuad.MaterialInfo info = quad.materialInfo();
                sprites.add(info.sprite());
                tints.add(info.isTinted() ? tint(state, info.tintIndex()) : -1);
            }
        }
        if (sprites.isEmpty()) { // nothing on that side: the block's particle texture
            sprites.add(model.particleMaterial().sprite());
            tints.add(-1);
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < sprites.size(); i++) {
            Identifier name = sprites.get(i).contents().name();
            if (i > 0) key.append('+');
            key.append(name.getNamespace()).append('_').append(name.getPath().replace('/', '_'));
            if (tints.get(i) != -1) key.append(String.format("_%06x", tints.get(i) & 0xFFFFFF));
        }
        String file = key.length() <= 80 ? key + ".png"
                : key.substring(0, 60) + "_" + Integer.toHexString(key.toString().hashCode()) + ".png";
        if (exported.add(file)) {
            BufferedImage image = null;
            for (int i = 0; i < sprites.size(); i++) {
                BufferedImage layer = read(sprites.get(i).contents().name());
                if (tints.get(i) != -1) multiply(layer, tints.get(i));
                image = image == null ? layer : over(image, layer);
            }
            write(scaled(image), folder.resolve(file));
        }
        return file;
    }

    private int tint(BlockState state, int index) {
        BlockTintSource source = minecraft.getBlockColors().getTintSource(state, index);
        return source == null ? -1 : source.color(state);
    }

    /** A block texture from the resource packs, its first frame if it is animated. */
    private BufferedImage read(Identifier sprite) throws IOException {
        Identifier file = Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        Resource resource = minecraft.getResourceManager().getResource(file)
                .orElseThrow(() -> new IOException("no resource " + file));
        BufferedImage image;
        try (InputStream in = resource.open()) {
            image = ImageIO.read(in);
        }
        if (image == null) throw new IOException("unreadable " + file);
        int size = image.getWidth();
        BufferedImage frame = new BufferedImage(size, Math.min(size, image.getHeight()), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < size; x++) frame.setRGB(x, y, image.getRGB(x, y));
        }
        return frame;
    }

    private static void multiply(BufferedImage image, int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                image.setRGB(x, y, p & 0xFF000000 | ((p >> 16 & 0xFF) * r / 255) << 16 | ((p >> 8 & 0xFF) * g / 255) << 8
                        | (p & 0xFF) * b / 255);
            }
        }
    }

    /** {@code top} drawn over {@code base} by its alpha (same size; the base keeps its own). */
    private static BufferedImage over(BufferedImage base, BufferedImage top) {
        for (int y = 0; y < Math.min(base.getHeight(), top.getHeight()); y++) {
            for (int x = 0; x < Math.min(base.getWidth(), top.getWidth()); x++) {
                int t = top.getRGB(x, y), b = base.getRGB(x, y), a = t >>> 24;
                if (a == 0) continue;
                int r = ((t >> 16 & 0xFF) * a + (b >> 16 & 0xFF) * (255 - a)) / 255;
                int g = ((t >> 8 & 0xFF) * a + (b >> 8 & 0xFF) * (255 - a)) / 255;
                int bl = ((t & 0xFF) * a + (b & 0xFF) * (255 - a)) / 255;
                base.setRGB(x, y, b & 0xFF000000 | r << 16 | g << 8 | bl);
            }
        }
        return base;
    }

    /** Each pixel as a SCALE x SCALE square (nearest neighbour). */
    private static BufferedImage scaled(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth() * SCALE, image.getHeight() * SCALE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, image.getRGB(x / SCALE, y / SCALE));
        }
        return out;
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        if (!ImageIO.write(image, "png", partial.toFile())) throw new IOException("no PNG writer");
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writePalette() {
        Path file = folder.resolve(PALETTE);
        Path partial = folder.resolve(PALETTE + ".partial");
        try {
            Files.write(partial, palette, StandardCharsets.UTF_8);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.accept("cannot write " + file + ": " + e);
        }
    }
}
