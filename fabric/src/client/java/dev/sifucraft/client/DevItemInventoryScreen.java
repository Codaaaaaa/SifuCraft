package dev.sifucraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** Vanilla survival inventory plus a searchable copy of the vanilla creative catalog. */
public final class DevItemInventoryScreen extends InventoryScreen {
    private record Entry(ItemStack stack, String id, String search) { }
    private final List<Entry> catalog = new ArrayList<>();
    private List<Entry> visible = List.of();
    private EditBox search;
    int catalogX, gridY, columns, rows;
    private int page;
    private boolean panelPress;
    String status = "左键一组 / 右键一个";

    public DevItemInventoryScreen(Player player) { super(player); }

    @Override protected void init() {
        String query = search == null ? "" : search.getValue();
        super.init();
        layoutCatalog();
        search = addRenderableWidget(new EditBox(font, catalogX + 4, topPos + 16, columns * 18, 18, Component.literal("搜索测试物品")));
        search.setHint(Component.literal("名称 / 拼音 / ID"));
        search.setMaxLength(80);
        CreativeModeTabs.tryRebuildTabContents(minecraft.level.enabledFeatures(), true, minecraft.level.registryAccess());
        catalog.clear();
        for (var item : CreativeModeTabs.searchTab().getDisplayItems()) addToCatalog(item.copy());
        var enchantments = minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (int density : new int[]{0, 5}) {
            ItemStack mace = new ItemStack(Items.MACE);
            mace.enchant(enchantments.getOrThrow(Enchantments.WIND_BURST), 3);
            if (density > 0) mace.enchant(enchantments.getOrThrow(Enchantments.DENSITY), density);
            mace.set(DataComponents.CUSTOM_NAME, Component.literal(density > 0 ? "测试重锤（致密 V + 风爆 III）" : "测试重锤（风爆 III）"));
            addToCatalog(mace);
        }
        ItemStack flightRocket = new ItemStack(Items.FIREWORK_ROCKET);
        flightRocket.set(DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(3, List.of()));
        flightRocket.set(DataComponents.CUSTOM_NAME, Component.literal("测试飞行烟花（持续 III，无烟火之星）"));
        addToCatalog(flightRocket);
        search.setResponder(this::filter);
        search.setValue(query);
        filter(query);
    }

    private void addToCatalog(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String name = stack.getHoverName().getString();
        String text = name + " " + pinyin(name) + " " + id + " " + stack.getComponents();
        catalog.add(new Entry(stack, id, text.toLowerCase(Locale.ROOT)));
    }

    // Minecraft already ships ICU4J; one reading per character, so a polyphone may index under its other sound.
    private static final java.util.Map<String, String> PINYIN = new java.util.HashMap<>();
    private static com.ibm.icu.text.Transliterator hanLatin;
    /** 钻石剑 → "zuan shi jian zuanshijian zsj": spaced, joined and initials. */
    private static String pinyin(String name) {
        if (name.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) return "";
        return PINYIN.computeIfAbsent(name, n -> {
            if (hanLatin == null) hanLatin = com.ibm.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower");
            String spaced = hanLatin.transliterate(n).strip();
            var initials = new StringBuilder();
            for (String word : spaced.split("[^a-z0-9]+")) if (!word.isEmpty()) initials.append(word.charAt(0));
            return spaced + " " + spaced.replaceAll("[^a-z0-9]", "") + " " + initials;
        });
    }

    private void layoutCatalog() {
        catalogX = leftPos + imageWidth + 6;
        columns = Math.max(1, Math.min(6, (width - catalogX - 8) / 18));
        rows = Math.max(1, (Math.min(imageHeight, height - topPos) - 76) / 18);
        gridY = topPos + 40;
        if (search != null) { search.setX(catalogX + 4); search.setY(topPos + 16); search.setWidth(columns * 18); }
        page = Math.min(page, pages() - 1);
    }

    @Override protected void onRecipeBookButtonClick() { super.onRecipeBookButtonClick(); layoutCatalog(); }

    private void filter(String query) {
        String text = query.toLowerCase(Locale.ROOT).strip();
        visible = catalog.stream().filter(e -> e.search.contains(text))
                .sorted(java.util.Comparator.comparingInt((Entry e) -> e.id.equals(text) ? 0 : e.id.contains(text) ? 1 : 2)).toList();
        page = 0;
    }

    private int pages() { return Math.max(1, (visible.size() + columns * rows - 1) / (columns * rows)); }
    private int footer() { return gridY + rows * 18 + 4; }
    private boolean panel(double x, double y) { return x >= catalogX && x < catalogX + columns * 18 + 8 && y >= topPos && y < footer() + 26; }
    private int cell(double x, double y) {
        int col = (int) Math.floor((x - catalogX - 4) / 18), row = (int) Math.floor((y - gridY) / 18);
        if (col < 0 || col >= columns || row < 0 || row >= rows) return -1;
        int index = page * columns * rows + row * columns + col;
        return index < visible.size() ? index : -1;
    }

    @Override public void extractBackground(GuiGraphicsExtractor gui, int x, int y, float partial) {
        super.extractBackground(gui, x, y, partial);
        gui.fill(catalogX, topPos, catalogX + columns * 18 + 8, footer() + 26, 0xEF242424);
        gui.text(font, "测试物品", catalogX + 4, topPos + 4, 0xFFFFFFFF);
        for (int n = 0; n < columns * rows; ++n) {
            int index = page * columns * rows + n;
            if (index >= visible.size()) break;
            int gx = catalogX + 4 + n % columns * 18, gy = gridY + n / columns * 18;
            gui.fill(gx, gy, gx + 18, gy + 18, cell(x, y) == index ? 0xFF777777 : 0xFF444444);
            gui.item(visible.get(index).stack, gx + 1, gy + 1);
        }
        gui.text(font, "< " + (page + 1) + "/" + pages() + " >", catalogX + 4, footer(), 0xFFFFFFFF);
        gui.text(font, font.plainSubstrByWidth(status, columns * 18), catalogX + 4, footer() + 13, 0xFFCCCCCC);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor gui, int x, int y, float partial) {
        super.extractRenderState(gui, x, y, partial);
        int index = cell(x, y);
        if (index >= 0) gui.setTooltipForNextFrame(font, visible.get(index).stack, x, y);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!panel(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        panelPress = true;
        if (search.mouseClicked(event, doubleClick)) { setFocused(search); return true; }
        setFocused(null);
        int index = cell(event.x(), event.y());
        if (index >= 0 && (event.button() == 1 || event.button() == 3)) {
            var stack = visible.get(index).stack;
            DevItemAccess.grant(this, stack, event.button() == 1 ? stack.getMaxStackSize() : 1);
        } else if (event.y() >= footer() && event.y() < footer() + 11)
            page = Math.clamp(page + (event.x() < catalogX + (columns * 18 + 8) / 2.0 ? -1 : 1), 0, pages() - 1);
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (panelPress) { panelPress = false; return true; }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!panel(x, y)) return super.mouseScrolled(x, y, dx, dy);
        page = Math.clamp(page - (int) Math.signum(dy), 0, pages() - 1);
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (search.isFocused() && event.key() != 41) {
            search.keyPressed(event);
            return true; // Printable E belongs to search; Escape still closes the inventory.
        }
        return super.keyPressed(event);
    }
}
