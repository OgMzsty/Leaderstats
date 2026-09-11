package com.statsboard.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.statsboard.ProfileEntry;
import com.statsboard.config.StatsboardConfig;
import com.statsboard.mixin.PlayerEntityAccessor;
import com.statsboard.network.StatsboardNetworking;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatValueFormatter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A single player's full stat profile: a searchable, category-tabbed list on
 * the left (mirroring {@link StatPickerScreen}'s General/Items/Mobs split)
 * and a large 3D preview of the player on the right (mirroring
 * {@link LeaderboardScreen}'s podium rendering).
 *
 * <p>Like both of those screens, this only ever reads {@link StatKey#displayName()}
 * - never {@code StatKey#resolve()} or {@code StatType#getOrCreateStat} /
 * {@code getName()} - so it is safe to build and render entirely on the
 * client render thread. Each row's display name and lowercased search text
 * are computed once, when a profile arrives, not per frame or per keystroke.
 */
public class PlayerProfileScreen extends Screen {
    private enum Category { GENERAL, ITEMS, MOBS }

    /** One row of the profile: precomputed so render/search never reformat it. */
    private record Row(Category category, StatKey key, int value, Text displayName, String searchText) {
    }

    private static final int ROW_HEIGHT = 14;
    private static final int TABS_Y = 30;
    private static final int TAB_WIDTH = 90;
    private static final int TAB_HEIGHT = 20;
    private static final int SEARCH_Y = 56;
    private static final int SEARCH_HEIGHT = 16;
    private static final int PANEL_TOP = 78;
    private static final int PANEL_BOTTOM_MARGIN = 40;

    // Same palette as LeaderboardScreen/StatPickerScreen so all three read as one mod.
    private static final int PANEL_BORDER = 0x1A1A22;
    private static final int PANEL_TOP_COLOR = 0x2B2140;
    private static final int PANEL_BOTTOM_COLOR = 0x120C1E;
    private static final int GOLD_TRIM = 0xD4AF37;

    // If the 3D preview ever throws (e.g. a mapping mismatch on someone's
    // setup) we fall back to a flat head icon instead of crashing the screen.
    private static boolean use3DModel = true;

    private final Screen parent;
    private final UUID uuid;
    private final String name;
    private final String skinValue;
    private final String skinSignature;

    private List<Row> allRows = Collections.emptyList();
    private List<Row> filteredRows = Collections.emptyList();
    private Category currentCategory = Category.GENERAL;
    private double scrollOffset = 0;

    private long lastRequestMs = Util.getMeasuringTimeMs();

    // The fake client-side entity used for the 3D preview. Rebuilt only when
    // the world instance changes (e.g. reconnect), never once per frame.
    private LivingEntity cachedEntity;
    private ClientWorld cachedEntityWorld;

    private TextFieldWidget searchBox;
    private int leftX, leftWidth, rightX, rightWidth, panelBottom;
    private int backX, backY, backWidth, backHeight;
    private int tabWidth, generalX, itemsX, mobsX;

    /**
     * @param name          may be a raw UUID string when the server has no real
     *                      username on record; never null
     * @param skinValue     null for offline-mode players with no Mojang textures
     * @param skinSignature null alongside a null skinValue
     */
    public PlayerProfileScreen(Screen parent, UUID uuid, String name, String skinValue, String skinSignature,
                                List<ProfileEntry> entries) {
        super(Text.literal(name == null ? uuid.toString() : name));
        this.parent = parent;
        this.uuid = uuid;
        this.name = name == null ? uuid.toString() : name;
        this.skinValue = skinValue;
        this.skinSignature = skinSignature;
        buildRows(entries);
    }

    /**
     * Applies a profile the server pushed for an already-open screen. Ignored
     * unless it is for the player this screen shows - a screen left open while
     * switching targets elsewhere would otherwise render a stranger's stats.
     *
     * <p>Preserves scroll position, the active tab and the search text: only
     * the row data is rebuilt, then the existing filter is reapplied and the
     * scroll offset is clamped in case the list shrank.
     */
    public void acceptProfile(UUID incomingUuid, List<ProfileEntry> incoming) {
        if (!uuid.equals(incomingUuid)) {
            return;
        }
        buildRows(incoming);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll());
    }

    /** Categorises and sorts (by value, descending) once per profile update. */
    private void buildRows(List<ProfileEntry> entries) {
        List<Row> rows = new ArrayList<>(entries == null ? 0 : entries.size());
        if (entries != null) {
            for (ProfileEntry entry : entries) {
                StatKey key = entry.key();
                Text displayName = key.displayName();
                String searchText = displayName.getString().toLowerCase(Locale.ROOT);
                rows.add(new Row(categorize(key), key, entry.value(), displayName, searchText));
            }
        }
        rows.sort((a, b) -> Integer.compare(b.value(), a.value()));
        this.allRows = rows;
        refreshFilter();
    }

    /**
     * Mirrors StatPickerScreen's three tabs. Anything that is not one of the
     * named vanilla stat types - including a modded stat type - falls into
     * General rather than being silently dropped from every tab.
     */
    private static Category categorize(StatKey key) {
        Identifier type = key.typeId();
        if (type.equals(StatKey.SPECIAL_TYPE)) {
            return Category.GENERAL;
        }
        if (!"minecraft".equals(type.getNamespace())) {
            return Category.GENERAL;
        }
        switch (type.getPath()) {
            case "mined":
            case "crafted":
            case "used":
            case "broken":
            case "picked_up":
            case "dropped":
                return Category.ITEMS;
            case "killed":
            case "killed_by":
                return Category.MOBS;
            case "custom":
            default:
                return Category.GENERAL;
        }
    }

    private void switchCategory(Category category) {
        if (category == currentCategory) {
            return;
        }
        this.currentCategory = category;
        refreshFilter();
        scrollOffset = 0;
    }

    /** Recomputes the visible rows from allRows; does not touch scrollOffset. */
    private void refreshFilter() {
        String needle = searchBox == null ? "" : searchBox.getText().toLowerCase(Locale.ROOT);
        List<Row> visible = new ArrayList<>();
        for (Row row : allRows) {
            if (row.category() == currentCategory && (needle.isEmpty() || row.searchText().contains(needle))) {
                visible.add(row);
            }
        }
        filteredRows = visible;
    }

    @Override
    protected void init() {
        leftX = 20;
        leftWidth = this.width / 2 - 30;
        rightX = this.width / 2 + 10;
        rightWidth = this.width - rightX - 20;
        panelBottom = this.height - PANEL_BOTTOM_MARGIN;

        // init() runs again on every resize, so the old widget's text has to be
        // carried over or the box would blank while the list stayed filtered.
        String previousSearch = searchBox == null ? "" : searchBox.getText();

        searchBox = new TextFieldWidget(this.textRenderer, leftX + 6, SEARCH_Y, leftWidth - 12, SEARCH_HEIGHT,
                Text.translatable("statsboard.picker.search"));
        searchBox.setPlaceholder(Text.translatable("statsboard.picker.search"));
        searchBox.setText(previousSearch);
        searchBox.setChangedListener(text -> {
            refreshFilter();
            scrollOffset = 0;
        });
        this.addSelectableChild(searchBox);

        // Tabs are laid out here rather than in render so the drawing and the
        // hit testing cannot drift apart, and the width shrinks to fit rather
        // than running off the left edge on a narrow window or a large GUI scale.
        int gap = 4;
        tabWidth = Math.min(TAB_WIDTH, Math.max(40, (leftWidth - gap * 2) / 3));
        int tabsStartX = leftX + leftWidth / 2 - (tabWidth * 3 + gap * 2) / 2;
        generalX = tabsStartX;
        itemsX = tabsStartX + tabWidth + gap;
        mobsX = tabsStartX + (tabWidth + gap) * 2;

        backWidth = 80;
        backHeight = 20;
        backX = this.width / 2 - backWidth / 2;
        backY = this.height - 30;

        refreshFilter();
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll());
    }

    /**
     * Re-asks the server for this player's profile so a screen left open keeps
     * up with play. Interval comes from client config; zero disables it.
     */
    @Override
    public void tick() {
        super.tick();

        long interval = StatsboardConfig.get().refreshIntervalMs();
        if (interval <= 0) {
            return;
        }
        if (Util.getMeasuringTimeMs() - lastRequestMs >= interval) {
            requestProfile();
        }
    }

    private void requestProfile() {
        lastRequestMs = Util.getMeasuringTimeMs();
        ProfileRequests.requestRefresh(uuid);
    }

    private int maxScroll() {
        int visibleHeight = panelBottom - PANEL_TOP - 10;
        return Math.max(0, filteredRows.size() * ROW_HEIGHT - visibleHeight);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(name).formatted(net.minecraft.util.Formatting.BOLD, net.minecraft.util.Formatting.GOLD),
                this.width / 2, 10, 0xFFFFFF);

        drawStyledButton(context, generalX, TABS_Y, tabWidth, TAB_HEIGHT, Text.translatable("statsboard.picker.general"),
                currentCategory == Category.GENERAL, isInside(mouseX, mouseY, generalX, TABS_Y, tabWidth, TAB_HEIGHT));
        drawStyledButton(context, itemsX, TABS_Y, tabWidth, TAB_HEIGHT, Text.translatable("statsboard.picker.items"),
                currentCategory == Category.ITEMS, isInside(mouseX, mouseY, itemsX, TABS_Y, tabWidth, TAB_HEIGHT));
        drawStyledButton(context, mobsX, TABS_Y, tabWidth, TAB_HEIGHT, Text.translatable("statsboard.picker.mobs"),
                currentCategory == Category.MOBS, isInside(mouseX, mouseY, mobsX, TABS_Y, tabWidth, TAB_HEIGHT));

        drawStyledPanel(context, leftX, PANEL_TOP, leftWidth, panelBottom - PANEL_TOP, 1f);
        renderList(context, leftX, PANEL_TOP, leftWidth, panelBottom - PANEL_TOP, mouseX, mouseY);

        drawStyledPanel(context, rightX, PANEL_TOP, rightWidth, panelBottom - PANEL_TOP, 1f);
        renderPlayerModel(context, rightX, PANEL_TOP, rightWidth, panelBottom - PANEL_TOP, mouseX, mouseY);

        drawStyledButton(context, backX, backY, backWidth, backHeight, Text.translatable("gui.back"),
                false, isInside(mouseX, mouseY, backX, backY, backWidth, backHeight));

        searchBox.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    private boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Same bordered-gradient look as the other two screens' buttons. */
    private void drawStyledButton(DrawContext context, int x, int y, int w, int h, Text label,
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

    /** Bordered gradient panel, matching the other two screens' drawStyledPanel. */
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

    private void renderList(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        int contentTop = y + 6;
        int contentBottom = y + height - 4;

        if (filteredRows.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("statsboard.picker.no_matches"),
                    x + 8, contentTop + 4, 0xFFAAAAAA);
            return;
        }

        context.enableScissor(x, contentTop, x + width, contentBottom);

        int rowY = contentTop - (int) scrollOffset;
        for (Row row : filteredRows) {
            if (rowY + ROW_HEIGHT >= contentTop && rowY <= contentBottom) {
                boolean hovered = isInside(mouseX, mouseY, x + 4, rowY - 1, width - 8, ROW_HEIGHT);
                if (hovered) {
                    context.fill(x + 4, rowY - 1, x + width - 4, rowY + ROW_HEIGHT - 3, 0x33FFFFFF);
                }
                context.drawTextWithShadow(this.textRenderer, row.displayName(), x + 8, rowY, 0xFFE8E0F0);

                String valueText = StatValueFormatter.format(row.key(), row.value());
                int valueWidth = this.textRenderer.getWidth(valueText);
                context.drawTextWithShadow(this.textRenderer, valueText, x + width - 8 - valueWidth, rowY, 0xFFFFFFFF);
            }
            rowY += ROW_HEIGHT;
        }

        context.disableScissor();
    }

    private void renderPlayerModel(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);

        int centerX = x + width / 2;
        int baseY = y + height - 10;
        int size = MathHelper.clamp(Math.min(width, height) - 30, 40, 100);

        boolean drewModel = false;
        if (use3DModel) {
            LivingEntity entity = getOrCreatePlayerEntity();
            if (entity != null) {
                try {
                    int modelTop = baseY - size;
                    int centerY = (modelTop + baseY) / 2;
                    float rotX = (float) Math.atan((centerX - mouseX) / 40.0);
                    float rotY = (float) Math.atan((centerY - mouseY) / 40.0);
                    InventoryScreen.drawEntity(context, centerX, baseY, size, rotX, rotY, entity);
                    drewModel = true;
                } catch (Throwable t) {
                    use3DModel = false;
                    System.err.println("[Statsboard] 3D player model rendering failed, "
                            + "falling back to a head icon: " + t);
                }
            }
        }
        if (!drewModel) {
            int headSize = Math.min(width, height) / 2;
            drawPlayerHead(context, getSkinTexture(), centerX - headSize / 2, y + height / 2 - headSize / 2, headSize);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(name),
                    centerX, y + height / 2 + headSize / 2 + 6, 0xFFFFFFFF);
        }

        context.disableScissor();
    }

    /** Builds (and caches) a fake client-side player entity purely for the 3D preview. */
    private LivingEntity getOrCreatePlayerEntity() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            cachedEntity = null;
            cachedEntityWorld = null;
            return null;
        }
        if (cachedEntity != null && cachedEntityWorld == world) {
            return cachedEntity;
        }

        GameProfile profile = new GameProfile(uuid, name);
        if (skinValue != null && !skinValue.isEmpty()) {
            profile.getProperties().put("textures", new Property("textures", skinValue, skinSignature));
        }
        OtherClientPlayerEntity entity = new OtherClientPlayerEntity(world, profile);
        // Fake entities don't inherit the real player's skin-layer visibility
        // settings (jacket, sleeves, hat brim, etc.) - force them all on.
        entity.getDataTracker().set(PlayerEntityAccessor.getPlayerModelParts(), (byte) 0x7F);
        cachedEntity = entity;
        cachedEntityWorld = world;
        return entity;
    }

    /**
     * Uses the live skin when available, otherwise the normal deterministic
     * Steve/Alex skin - the saved texture property is only wired up for the 3D
     * preview above, not worth a dynamic texture registration for the flat
     * fallback that should rarely even be seen.
     */
    private Identifier getSkinTexture() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            PlayerListEntry live = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (live != null) {
                return live.getSkinTexture();
            }
        }
        return DefaultSkinHelper.getTexture(uuid);
    }

    private void drawPlayerHead(DrawContext context, Identifier skin, int x, int y, int size) {
        float scale = size / 8.0f;
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale(scale, scale, 1.0f);
        // Base face layer, then the hat/overlay layer on top of it.
        context.drawTexture(skin, 0, 0, 8, 8, 8, 8, 64, 64);
        context.drawTexture(skin, 0, 0, 40, 8, 8, 8, 64, 64);
        context.getMatrices().pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int mx = (int) mouseX;
            int my = (int) mouseY;

            if (isInside(mx, my, generalX, TABS_Y, tabWidth, TAB_HEIGHT)) {
                switchCategory(Category.GENERAL);
                return true;
            }
            if (isInside(mx, my, itemsX, TABS_Y, tabWidth, TAB_HEIGHT)) {
                switchCategory(Category.ITEMS);
                return true;
            }
            if (isInside(mx, my, mobsX, TABS_Y, tabWidth, TAB_HEIGHT)) {
                switchCategory(Category.MOBS);
                return true;
            }
            if (isInside(mx, my, backX, backY, backWidth, backHeight)) {
                this.close();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset -= amount * ROW_HEIGHT * 2;
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll());
        return true;
    }

    @Override
    public void close() {
        ProfileRequests.clear();
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
