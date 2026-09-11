package com.statsboard.gui;

import com.statsboard.stat.StatKey;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Lets the player pick any vanilla statistic, mirroring the three categories
 * of the vanilla Statistics screen (General / Items / Mobs).
 *
 * <p>This screen only ever reads registries and {@link StatKey#displayName()}
 * - see the threading note on {@link StatKey} - so it never calls
 * {@code StatType#getOrCreateStat} or {@code StatType#getName()}, which are
 * server-thread-only. Every row is built once into a static cache the first
 * time this screen is opened, since walking six registries and formatting
 * ~8000 display names on every open would be wasteful.
 */
public class StatPickerScreen extends Screen {
    private enum Category { GENERAL, ITEMS, MOBS }

    /** One pickable stat, with its filter text precomputed so search doesn't reformat it per keystroke. */
    private record Row(Category category, StatKey key, Text displayName, String searchText) {
    }

    private static final Identifier CUSTOM = new Identifier("minecraft", "custom");
    private static final Identifier MINED = new Identifier("minecraft", "mined");
    private static final Identifier CRAFTED = new Identifier("minecraft", "crafted");
    private static final Identifier USED = new Identifier("minecraft", "used");
    private static final Identifier BROKEN = new Identifier("minecraft", "broken");
    private static final Identifier PICKED_UP = new Identifier("minecraft", "picked_up");
    private static final Identifier DROPPED = new Identifier("minecraft", "dropped");
    private static final Identifier KILLED = new Identifier("minecraft", "killed");
    private static final Identifier KILLED_BY = new Identifier("minecraft", "killed_by");

    private static final int ROW_HEIGHT = 14;
    private static final int TABS_Y = 30;
    private static final int TAB_WIDTH = 90;
    private static final int TAB_HEIGHT = 20;
    private static final int SEARCH_Y = 56;
    private static final int SEARCH_HEIGHT = 16;
    private static final int PANEL_TOP = 78;
    private static final int PANEL_BOTTOM_MARGIN = 40;

    // Same palette as LeaderboardScreen so this reads as part of the same mod.
    private static final int PANEL_BORDER = 0x1A1A22;
    private static final int PANEL_TOP_COLOR = 0x2B2140;
    private static final int PANEL_BOTTOM_COLOR = 0x120C1E;
    private static final int GOLD_TRIM = 0xD4AF37;

    /** Built once on first open; walking the registries is too costly to repeat per open. */
    private static List<Row> allRows;

    private final Screen parent;
    private final Consumer<StatKey> onPicked;

    private Category currentCategory = Category.GENERAL;
    private List<Row> filteredRows = Collections.emptyList();
    private double scrollOffset = 0;

    private TextFieldWidget searchBox;
    private int panelX, panelWidth, panelBottom;
    private int closeX, closeY, closeWidth, closeHeight;

    public StatPickerScreen(Screen parent, Consumer<StatKey> onPicked) {
        super(Text.literal("Pick a Stat"));
        this.parent = parent;
        this.onPicked = onPicked;
    }

    @Override
    protected void init() {
        ensureRowsBuilt();

        panelX = 20;
        panelWidth = this.width - 40;
        panelBottom = this.height - PANEL_BOTTOM_MARGIN;

        searchBox = new TextFieldWidget(this.textRenderer, panelX + 6, SEARCH_Y, panelWidth - 12, SEARCH_HEIGHT,
                Text.literal("Search"));
        searchBox.setPlaceholder(Text.literal("Search stats..."));
        searchBox.setChangedListener(text -> refreshFilter());
        this.addSelectableChild(searchBox);
        this.setInitialFocus(searchBox);

        closeWidth = 80;
        closeHeight = 20;
        closeX = this.width / 2 - closeWidth / 2;
        closeY = this.height - 30;

        refreshFilter();
    }

    /** Populates the static row cache. Idempotent - only the first call does anything. */
    private static void ensureRowsBuilt() {
        if (allRows != null) {
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (Identifier id : Registries.CUSTOM_STAT.getIds()) {
            addRow(rows, Category.GENERAL, new StatKey(CUSTOM, id));
        }

        // mined is a StatType<Block>, so its values come from BLOCK, not ITEM.
        for (Identifier id : Registries.BLOCK.getIds()) {
            addRow(rows, Category.ITEMS, new StatKey(MINED, id));
        }
        for (Identifier id : Registries.ITEM.getIds()) {
            addRow(rows, Category.ITEMS, new StatKey(CRAFTED, id));
            addRow(rows, Category.ITEMS, new StatKey(USED, id));
            addRow(rows, Category.ITEMS, new StatKey(BROKEN, id));
            addRow(rows, Category.ITEMS, new StatKey(PICKED_UP, id));
            addRow(rows, Category.ITEMS, new StatKey(DROPPED, id));
        }

        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            addRow(rows, Category.MOBS, new StatKey(KILLED, id));
            addRow(rows, Category.MOBS, new StatKey(KILLED_BY, id));
        }

        allRows = rows;
    }

    private static void addRow(List<Row> rows, Category category, StatKey key) {
        Text displayName = key.displayName();
        String searchText = displayName.getString().toLowerCase(Locale.ROOT);
        rows.add(new Row(category, key, displayName, searchText));
    }

    private void switchCategory(Category category) {
        if (category == currentCategory) {
            return;
        }
        this.currentCategory = category;
        refreshFilter();
    }

    /** Recomputes the visible rows. Called on search input and category switch only, not per frame. */
    private void refreshFilter() {
        String needle = searchBox == null ? "" : searchBox.getText().toLowerCase(Locale.ROOT);
        List<Row> source = allRows.stream().filter(r -> r.category() == currentCategory).collect(Collectors.toList());
        if (needle.isEmpty()) {
            filteredRows = source;
        } else {
            filteredRows = source.stream().filter(r -> r.searchText().contains(needle)).collect(Collectors.toList());
        }
        scrollOffset = 0;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        context.drawCenteredTextWithShadow(this.textRenderer, "§l§6Pick a Stat", this.width / 2, 10, 0xFFFFFF);

        int centerX = this.width / 2;
        int tabsStartX = centerX - (TAB_WIDTH * 3 + 8) / 2;
        int generalX = tabsStartX;
        int itemsX = tabsStartX + TAB_WIDTH + 4;
        int mobsX = tabsStartX + (TAB_WIDTH + 4) * 2;

        drawStyledButton(context, generalX, TABS_Y, TAB_WIDTH, TAB_HEIGHT, "General",
                currentCategory == Category.GENERAL, isInside(mouseX, mouseY, generalX, TABS_Y, TAB_WIDTH, TAB_HEIGHT));
        drawStyledButton(context, itemsX, TABS_Y, TAB_WIDTH, TAB_HEIGHT, "Items",
                currentCategory == Category.ITEMS, isInside(mouseX, mouseY, itemsX, TABS_Y, TAB_WIDTH, TAB_HEIGHT));
        drawStyledButton(context, mobsX, TABS_Y, TAB_WIDTH, TAB_HEIGHT, "Mobs",
                currentCategory == Category.MOBS, isInside(mouseX, mouseY, mobsX, TABS_Y, TAB_WIDTH, TAB_HEIGHT));

        drawStyledPanel(context, panelX, PANEL_TOP, panelWidth, panelBottom - PANEL_TOP, 1f);
        renderList(context, panelX, PANEL_TOP, panelWidth, panelBottom - PANEL_TOP);

        drawStyledButton(context, closeX, closeY, closeWidth, closeHeight, "Cancel",
                false, isInside(mouseX, mouseY, closeX, closeY, closeWidth, closeHeight));

        searchBox.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    private boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Same bordered-gradient look as LeaderboardScreen's buttons, so the two screens match. */
    private void drawStyledButton(DrawContext context, int x, int y, int w, int h, String label,
                                   boolean active, boolean hovered) {
        context.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF1A1A22);

        int topColor = active ? 0xFF4A3A6E : hovered ? 0xFF362A50 : 0xFF241B37;
        int bottomColor = active ? 0xFF2E2348 : hovered ? 0xFF221A34 : 0xFF16101F;
        context.fillGradient(x, y, x + w, y + h, 0xFF000000 | topColor, 0xFF000000 | bottomColor);

        int trim = active ? (0xFF000000 | GOLD_TRIM) : hovered ? 0xFFE8C96A : 0xFF6E6280;
        context.fill(x, y, x + w, y + 1, trim);
        context.fill(x, y + h - 1, x + w, y + h, trim);
        context.fill(x, y, x + 1, y + h, trim);
        context.fill(x + w - 1, y, x + w, y + h, trim);

        int textColor = active ? (0xFF000000 | GOLD_TRIM) : 0xFFE8E0F0;
        context.drawCenteredTextWithShadow(this.textRenderer, label, x + w / 2, y + h / 2 - 4, textColor);
    }

    /** Bordered gradient panel, matching LeaderboardScreen's drawStyledPanel. */
    private void drawStyledPanel(DrawContext context, int x, int y, int w, int h, float alpha) {
        int a = (int) (alpha * 255) << 24;
        context.fill(x - 3, y - 3, x + w + 3, y + h + 3, a | PANEL_BORDER);
        context.fillGradient(x, y, x + w, y + h, a | PANEL_TOP_COLOR, a | PANEL_BOTTOM_COLOR);

        int trim = a | GOLD_TRIM;
        context.fill(x, y, x + w, y + 1, trim);
        context.fill(x, y + h - 1, x + w, y + h, trim);
        context.fill(x, y, x + 1, y + h, trim);
        context.fill(x + w - 1, y, x + w, y + h, trim);
    }

    private void renderList(DrawContext context, int x, int y, int width, int height) {
        int contentTop = y + 6;
        int contentBottom = y + height - 4;

        if (filteredRows.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "No matches.", x + 8, contentTop + 4, 0xFFAAAAAA);
            return;
        }

        context.enableScissor(x, contentTop, x + width, contentBottom);

        int rowY = contentTop - (int) scrollOffset;
        for (Row row : filteredRows) {
            if (rowY + ROW_HEIGHT >= contentTop && rowY <= contentBottom) {
                boolean hovered = isInside(lastMouseX, lastMouseY, x + 4, rowY - 1, width - 8, ROW_HEIGHT);
                if (hovered) {
                    context.fill(x + 4, rowY - 1, x + width - 4, rowY + ROW_HEIGHT - 3, 0x33FFFFFF);
                }
                context.drawTextWithShadow(this.textRenderer, row.displayName(), x + 8, rowY, 0xFFE8E0F0);
            }
            rowY += ROW_HEIGHT;
        }

        context.disableScissor();
    }

    // render() doesn't receive mouse coordinates in renderList's signature without threading them
    // through, and hover highlighting is a nice-to-have, not part of the spec - track the last
    // seen position instead of changing renderList's signature everywhere.
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        lastMouseX = (int) mouseX;
        lastMouseY = (int) mouseY;

        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        int centerX = this.width / 2;
        int tabsStartX = centerX - (TAB_WIDTH * 3 + 8) / 2;
        int generalX = tabsStartX;
        int itemsX = tabsStartX + TAB_WIDTH + 4;
        int mobsX = tabsStartX + (TAB_WIDTH + 4) * 2;

        if (isInside(mx, my, generalX, TABS_Y, TAB_WIDTH, TAB_HEIGHT)) {
            switchCategory(Category.GENERAL);
            return true;
        }
        if (isInside(mx, my, itemsX, TABS_Y, TAB_WIDTH, TAB_HEIGHT)) {
            switchCategory(Category.ITEMS);
            return true;
        }
        if (isInside(mx, my, mobsX, TABS_Y, TAB_WIDTH, TAB_HEIGHT)) {
            switchCategory(Category.MOBS);
            return true;
        }
        if (isInside(mx, my, closeX, closeY, closeWidth, closeHeight)) {
            this.close();
            return true;
        }

        int contentTop = PANEL_TOP + 6;
        int contentBottom = panelBottom - 4;
        if (mx >= panelX && mx < panelX + panelWidth && my >= contentTop && my < contentBottom) {
            int index = ((int) (my - contentTop + scrollOffset)) / ROW_HEIGHT;
            if (index >= 0 && index < filteredRows.size()) {
                StatKey picked = filteredRows.get(index).key();
                onPicked.accept(picked);
                this.client.setScreen(parent);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        lastMouseX = (int) mouseX;
        lastMouseY = (int) mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset -= amount * ROW_HEIGHT * 2;
        int visibleHeight = panelBottom - PANEL_TOP - 10;
        int maxScroll = Math.max(0, filteredRows.size() * ROW_HEIGHT - visibleHeight);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);
        return true;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
