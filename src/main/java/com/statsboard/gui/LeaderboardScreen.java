package com.statsboard.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.statsboard.LeaderboardEntry;
import com.statsboard.network.StatsboardNetworking;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatValueFormatter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import com.statsboard.mixin.PlayerEntityAccessor;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LeaderboardScreen extends Screen {
    private static final int ROW_HEIGHT = 14;
    private static final int PANEL_TOP = 60;
    private static final int PANEL_BOTTOM_MARGIN = 40;

    // Panel color palette - dark purple/navy gradient with a gold trim, to
    // read as a "leaderboard trophy case" rather than a plain overlay.
    private static final int PANEL_BORDER = 0x1A1A22;
    private static final int PANEL_TOP_COLOR = 0x2B2140;
    private static final int PANEL_BOTTOM_COLOR = 0x120C1E;
    private static final int GOLD_TRIM = 0xD4AF37;

    private static final long OPEN_ANIM_MS = 320;
    private static final long FADE_ANIM_MS = 260;

    // If real 3D player-model rendering ever throws (e.g. a mapping mismatch
    // on someone's setup), we silently fall back to flat head icons instead
    // of crashing the whole screen.
    private static boolean use3DModel = true;

    private final Map<UUID, OtherClientPlayerEntity> entityCache = new HashMap<>();
    private final long openTimeMs = System.currentTimeMillis();

    private List<LeaderboardEntry> entries;
    private StatKey statKey;
    /** The stat we last asked the server for; replies for anything else are stale. */
    private StatKey pendingKey;
    private double scrollOffset = 0;

    private int deathsTabX, advTabX, pickerX, tabsY, tabWidth, tabHeight;
    private int closeX, closeY, closeWidth, closeHeight;

    public LeaderboardScreen(StatKey statKey, List<LeaderboardEntry> entries) {
        super(Text.literal("Statsboard"));
        this.statKey = statKey;
        this.pendingKey = statKey;
        this.entries = entries;
    }

    /**
     * Applies a board the server sent for an already-open screen. Ignored unless
     * it answers the most recent request - there is no request id, so a player
     * who switches stat twice quickly could otherwise see the first reply land
     * after the second.
     */
    public void acceptBoard(StatKey key, List<LeaderboardEntry> incoming) {
        if (!key.equals(pendingKey)) {
            return;
        }
        this.statKey = key;
        this.entries = incoming;
        this.scrollOffset = 0;
        this.entityCache.clear();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        tabWidth = 100;
        tabHeight = 20;
        tabsY = 30;
        deathsTabX = centerX - 156;
        advTabX = centerX - 50;
        pickerX = centerX + 56;

        closeWidth = 80;
        closeHeight = 20;
        closeX = centerX - closeWidth / 2;
        closeY = this.height - 30;
    }

    /** Asks the server for a different stat; the reply arrives via acceptBoard. */
    private void requestStat(StatKey key) {
        this.pendingKey = key;
        PacketByteBuf buf = PacketByteBufs.create();
        key.write(buf);
        buf.writeInt(50);
        ClientPlayNetworking.send(StatsboardNetworking.REQUEST_BOARD, buf);
    }

    private List<LeaderboardEntry> currentList() {
        return entries;
    }

    /** 0 at the moment the screen opens, easing up to 1 over durationMs. */
    private float easedProgress(long durationMs) {
        float t = MathHelper.clamp((System.currentTimeMillis() - openTimeMs) / (float) durationMs, 0f, 1f);
        return 1 - (1 - t) * (1 - t); // ease-out
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        float eased = easedProgress(OPEN_ANIM_MS);
        int slideOffset = (int) ((1 - eased) * 18);

        int panelBottom = this.height - PANEL_BOTTOM_MARGIN;
        int leftX = 20;
        int leftWidth = this.width / 2 - 30;
        int rightX = this.width / 2 + 10;
        int rightWidth = this.width - rightX - 20;

        context.drawCenteredTextWithShadow(this.textRenderer, "\u00a7l\u00a76Statsboard", this.width / 2, 10, 0xFFFFFF);

        context.getMatrices().push();
        context.getMatrices().translate(0, slideOffset, 0);

        drawStyledPanel(context, leftX, PANEL_TOP, leftWidth, panelBottom - PANEL_TOP, eased);
        drawStyledPanel(context, rightX, PANEL_TOP, rightWidth, panelBottom - PANEL_TOP, eased);

        renderList(context, leftX, PANEL_TOP, leftWidth, panelBottom - PANEL_TOP);
        renderPodium(context, rightX, PANEL_TOP, rightWidth, panelBottom - PANEL_TOP, mouseX, mouseY);

        context.getMatrices().pop();

        drawStyledButton(context, deathsTabX, tabsY, tabWidth, tabHeight, StatKey.DEATHS.displayName(),
                StatKey.DEATHS.equals(statKey), isInside(mouseX, mouseY, deathsTabX, tabsY, tabWidth, tabHeight));
        drawStyledButton(context, advTabX, tabsY, tabWidth, tabHeight, StatKey.ADVANCEMENTS.displayName(),
                StatKey.ADVANCEMENTS.equals(statKey),
                isInside(mouseX, mouseY, advTabX, tabsY, tabWidth, tabHeight));
        drawStyledButton(context, pickerX, tabsY, tabWidth, tabHeight, Text.translatable("statsboard.screen.change_stat"),
                false, isInside(mouseX, mouseY, pickerX, tabsY, tabWidth, tabHeight));
        drawStyledButton(context, closeX, closeY, closeWidth, closeHeight, Text.translatable("gui.done"),
                false, isInside(mouseX, mouseY, closeX, closeY, closeWidth, closeHeight));

        super.render(context, mouseX, mouseY, delta);

        // Full-screen wash that fades out over the first ~260ms, masking the
        // whole GUI (panels, text and buttons alike) popping into place.
        float fadeEased = easedProgress(FADE_ANIM_MS);
        int washAlpha = (int) ((1 - fadeEased) * 170);
        if (washAlpha > 0) {
            context.fill(0, 0, this.width, this.height, (washAlpha << 24));
        }
    }

    private boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Same bordered-gradient look as the panels, so the buttons read as part of the same UI. */
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

    /** Bordered gradient panel instead of a flat translucent rectangle. */
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
        List<LeaderboardEntry> list = currentList();
        context.drawTextWithShadow(this.textRenderer, statKey.displayName(), x + 8, y + 6,
                GOLD_TRIM | 0xFF000000);
        context.fill(x + 6, y + 17, x + width - 6, y + 18, (GOLD_TRIM | 0xFF000000) & 0x66FFFFFF);

        int contentTop = y + 22;
        int contentBottom = y + height - 4;

        if (list.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("statsboard.screen.no_data"),
                    x + 8, contentTop + 4, 0xFFAAAAAA);
            return;
        }

        context.enableScissor(x, contentTop, x + width, contentBottom);

        int rowY = contentTop - (int) scrollOffset;
        int rank = 1;
        for (LeaderboardEntry entry : list) {
            if (rowY + ROW_HEIGHT >= contentTop && rowY <= contentBottom) {
                if (rank <= 3) {
                    int stripeColor = rank == 1 ? 0x33D4AF37 : rank == 2 ? 0x33C0C0C0 : 0x33CD7F32;
                    context.fill(x + 4, rowY - 1, x + width - 4, rowY + ROW_HEIGHT - 3, stripeColor);
                }
                int color = rank == 1 ? 0xFFFFD700 : rank == 2 ? 0xFFE0E0E0 : rank == 3 ? 0xFFCD7F32 : 0xFFFFFFFF;
                String line = rank + ". " + entry.name() + "  -  "
                        + StatValueFormatter.format(statKey, entry.count());
                context.drawTextWithShadow(this.textRenderer, line, x + 8, rowY, color);
            }
            rowY += ROW_HEIGHT;
            rank++;
        }

        context.disableScissor();
    }

    private void renderPodium(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
        List<LeaderboardEntry> list = currentList();
        context.drawTextWithShadow(this.textRenderer, "\u2726 Top 3", x + 8, y + 6, GOLD_TRIM | 0xFF000000);
        context.fill(x + 6, y + 17, x + width - 6, y + 18, (GOLD_TRIM | 0xFF000000) & 0x66FFFFFF);

        int baseY = y + height - 20;
        int slotWidth = width / 3;

        // Hard clip to the panel bounds so the 3D model can never visually
        // spill outside its box, regardless of how "size" actually scales.
        context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);

        // Visual order left-to-right: 2nd place, 1st place, 3rd place (classic podium look).
        int[] rankOrder = {1, 0, 2};
        int[] pedestalHeight = {40, 60, 28};
        int[] modelSize = {18, 24, 14};

        for (int slot = 0; slot < 3; slot++) {
            int rankIndex = rankOrder[slot];
            int slotX = x + slot * slotWidth;
            int centerX = slotX + slotWidth / 2;
            int pedestalTop = baseY - pedestalHeight[slot];

            int blockColor = rankIndex == 0 ? 0xFFFFD700 : rankIndex == 1 ? 0xFFC0C0C0 : 0xFFCD7F32;
            context.fill(slotX + 8, pedestalTop, slotX + slotWidth - 8, baseY, blockColor);
            context.fill(slotX + 8, pedestalTop, slotX + slotWidth - 8, pedestalTop + 2, 0x55FFFFFF);
            drawRankBadge(context, "#" + (rankIndex + 1), centerX, pedestalTop + pedestalHeight[slot] / 2);

            if (rankIndex < list.size()) {
                LeaderboardEntry entry = list.get(rankIndex);
                int size = modelSize[slot];
                int modelBottom = pedestalTop + 4;
                int modelTop = modelBottom - size;

                boolean drewModel = false;
                if (use3DModel) {
                    LivingEntity entity = getOrCreatePlayerEntity(entry.uuid(), entry.name());
                    if (entity != null) {
                        try {
                            int centerY = (modelTop + modelBottom) / 2;
                            float rotX = (float) Math.atan((centerX - mouseX) / 40.0);
                            float rotY = (float) Math.atan((centerY - mouseY) / 40.0);
                            InventoryScreen.drawEntity(context, centerX, modelBottom, size, rotX, rotY, entity);
                            drewModel = true;
                        } catch (Throwable t) {
                            use3DModel = false;
                            System.err.println("[Statsboard] 3D player model rendering failed, "
                                    + "falling back to head icons: " + t);
                        }
                    }
                }
                if (!drewModel) {
                    Identifier skin = getSkinTexture(entry.uuid(), entry);
                    int headSize = rankIndex == 0 ? 32 : 24;
                    drawPlayerHead(context, skin, centerX - headSize / 2, pedestalTop - headSize - 4, headSize);
                }

                context.drawCenteredTextWithShadow(this.textRenderer,
                        StatValueFormatter.format(statKey, entry.count()),
                        centerX, baseY + 4, 0xFFFFFFFF);
                if (!drewModel) {
                    // The 3D model already shows its own accurately-positioned
                    // nametag above the head - only add our own for the flat
                    // head-icon fallback, which has no nametag of its own.
                    context.drawCenteredTextWithShadow(this.textRenderer, entry.name(),
                            centerX, pedestalTop - 44, 0xFFFFFFFF);
                }
            } else {
                context.drawCenteredTextWithShadow(this.textRenderer, "-", centerX, pedestalTop - 20, 0xFF888888);
            }
        }

        context.disableScissor();
    }

    /** Builds (and caches) a fake client-side player entity purely for rendering a 3D preview. */
    private LivingEntity getOrCreatePlayerEntity(UUID uuid, String name) {
        if (entityCache.containsKey(uuid)) {
            return entityCache.get(uuid);
        }
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return null;
        }
        GameProfile profile = buildProfile(uuid, name);
        OtherClientPlayerEntity entity = new OtherClientPlayerEntity(world, profile);
        // Fake entities don't inherit the real player's skin-layer visibility
        // settings (jacket, sleeves, hat brim, etc.) - force them all on.
        entity.getDataTracker().set(PlayerEntityAccessor.getPlayerModelParts(), (byte) 0x7F);
        entityCache.put(uuid, entity);
        return entity;
    }

    /** Bigger, bold, white-on-dark-shadow rank number so it reads clearly against any pedestal color. */
    private void drawRankBadge(DrawContext context, String text, int centerX, int centerY) {
        context.getMatrices().push();
        context.getMatrices().translate(centerX, centerY, 0);
        context.getMatrices().scale(1.6f, 1.6f, 1f);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(text).formatted(
                net.minecraft.util.Formatting.BOLD), 0, -4, 0xFFFFFFFF);
        context.getMatrices().pop();
    }

    /**
     * Uses the live skin when available, otherwise reconstructs the saved
     * Mojang profile from the texture property sent by the server.
     */
    private Identifier getSkinTexture(UUID uuid, LeaderboardEntry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            PlayerListEntry live = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (live != null) {
                return live.getSkinTexture();
            }
        }

        // The saved texture property is attached to the fake 3D player profile.
        // If the renderer cannot use it for the flat fallback, use the normal
        // deterministic Steve/Alex skin instead of risking a GUI crash.
        return DefaultSkinHelper.getTexture(uuid);
    }

    private GameProfile buildProfile(UUID uuid, String name) {
        // This overload is used by the 3D preview. Find the matching entry so
        // its persisted skin property is attached to the fake player profile.
        LeaderboardEntry match = null;
        for (LeaderboardEntry entry : currentList()) {
            if (entry.uuid().equals(uuid)) {
                match = entry;
                break;
            }
        }
        return match == null
                ? new GameProfile(uuid, name)
                : buildProfile(uuid, name, match.skinTextureValue(), match.skinTextureSignature());
    }

    private GameProfile buildProfile(UUID uuid, String name, String skinValue, String skinSignature) {
        GameProfile profile = new GameProfile(uuid, name);
        if (skinValue != null && !skinValue.isEmpty()) {
            profile.getProperties().put("textures", new Property("textures", skinValue, skinSignature));
        }
        return profile;
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
            if (isInside(mx, my, deathsTabX, tabsY, tabWidth, tabHeight)) {
                requestStat(StatKey.DEATHS);
                return true;
            }
            if (isInside(mx, my, advTabX, tabsY, tabWidth, tabHeight)) {
                requestStat(StatKey.ADVANCEMENTS);
                return true;
            }
            if (isInside(mx, my, pickerX, tabsY, tabWidth, tabHeight)) {
                this.client.setScreen(new StatPickerScreen(this, this::requestStat));
                return true;
            }
            if (isInside(mx, my, closeX, closeY, closeWidth, closeHeight)) {
                this.close();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset -= amount * ROW_HEIGHT * 2;
        int visibleHeight = this.height - PANEL_BOTTOM_MARGIN - PANEL_TOP - 22;
        int maxScroll = Math.max(0, currentList().size() * ROW_HEIGHT - visibleHeight);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
