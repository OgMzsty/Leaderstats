package com.statsboard.gui;

import com.statsboard.network.StatsboardNetworking;
import com.statsboard.block.LeaderboardBlockEntity;
import com.statsboard.stat.StatKey;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown when a player pokes a leaderboard block: lists its columns so they can
 * choose which one to retarget before the stat picker opens.
 */
public class LeaderboardConfigScreen extends Screen {
    private static final int PANEL_BORDER = 0x1A1A22;
    private static final int PANEL_TOP_COLOR = 0x2B2140;
    private static final int PANEL_BOTTOM_COLOR = 0x120C1E;
    private static final int GOLD_TRIM = 0xD4AF37;

    private static final int ROW_WIDTH = 220;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_GAP = 6;
    private static final int SMALL_SIZE = 20;
    private static final int MAX_ROWS = 3;

    private final BlockPos pos;
    private final List<StatKey> columns;

    private int rowX;
    private int firstRowY;
    private int minusX, plusX, countY;

    public LeaderboardConfigScreen(BlockPos pos, List<StatKey> columns) {
        super(Text.translatable("statsboard.config.title"));
        this.pos = pos;
        this.columns = new ArrayList<>(columns);
    }

    @Override
    protected void init() {
        rowX = (this.width - ROW_WIDTH) / 2;
        firstRowY = this.height / 2 - (columns.size() * (ROW_HEIGHT + ROW_GAP)) / 2;
        countY = firstRowY + MAX_ROWS * (ROW_HEIGHT + ROW_GAP) + 8;
        minusX = this.width / 2 - 60;
        plusX = this.width / 2 + 40;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("statsboard.config.title"), this.width / 2, firstRowY - 28,
                GOLD_TRIM | 0xFF000000);

        for (int i = 0; i < columns.size(); i++) {
            int y = firstRowY + i * (ROW_HEIGHT + ROW_GAP);
            boolean hovered = isInside(mouseX, mouseY, rowX, y);
            drawStyledButton(context, rowX, y, ROW_WIDTH, ROW_HEIGHT,
                    Text.translatable("statsboard.config.column", i + 1)
                            .append(": ")
                            .append(columns.get(i).displayName()),
                    hovered);
        }

        // Column count controls, so the 1- and 3-column layouts are reachable.
        drawSmallButton(context, minusX, countY, "-", columns.size() > 1,
                isInsideSmall(mouseX, mouseY, minusX, countY));
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("statsboard.config.count", columns.size()),
                this.width / 2, countY + 6, 0xFFE8E0F0);
        drawSmallButton(context, plusX, countY, "+", columns.size() < LeaderboardBlockEntity.MAX_COLUMNS,
                isInsideSmall(mouseX, mouseY, plusX, countY));

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isInsideSmall((int) mouseX, (int) mouseY, minusX, countY) && columns.size() > 1) {
                applyCount(columns.size() - 1);
                return true;
            }
            if (isInsideSmall((int) mouseX, (int) mouseY, plusX, countY)
                    && columns.size() < LeaderboardBlockEntity.MAX_COLUMNS) {
                applyCount(columns.size() + 1);
                return true;
            }
            for (int i = 0; i < columns.size(); i++) {
                int y = firstRowY + i * (ROW_HEIGHT + ROW_GAP);
                if (isInside((int) mouseX, (int) mouseY, rowX, y)) {
                    final int columnIndex = i;
                    this.client.setScreen(new StatPickerScreen(this,
                            key -> apply(columnIndex, key)));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void apply(int columnIndex, StatKey key) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(columnIndex);
        key.write(buf);
        ClientPlayNetworking.send(StatsboardNetworking.SET_BLOCK_STAT, buf);

        // Reflect the change locally so returning here shows the new stat; the
        // server's own block update follows on the next refresh.
        columns.set(columnIndex, key);
    }

    private void applyCount(int count) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(count);
        ClientPlayNetworking.send(StatsboardNetworking.SET_COLUMN_COUNT, buf);

        // Mirror the server's own grow/shrink rule so the list redraws correctly
        // without waiting for the block update to come back.
        while (columns.size() > count) {
            columns.remove(columns.size() - 1);
        }
        while (columns.size() < count) {
            columns.add(columns.get(columns.size() - 1));
        }
        init();
    }

    private void drawSmallButton(DrawContext context, int x, int y, String label, boolean enabled, boolean hovered) {
        context.fill(x - 2, y - 2, x + SMALL_SIZE + 2, y + SMALL_SIZE + 2, 0xFF000000 | PANEL_BORDER);
        context.fillGradient(x, y, x + SMALL_SIZE, y + SMALL_SIZE,
                0xFF000000 | (hovered && enabled ? 0x362A50 : PANEL_TOP_COLOR),
                0xFF000000 | (hovered && enabled ? 0x221A34 : PANEL_BOTTOM_COLOR));
        context.drawCenteredTextWithShadow(this.textRenderer, label,
                x + SMALL_SIZE / 2, y + SMALL_SIZE / 2 - 4, enabled ? 0xFFE8E0F0 : 0xFF666666);
    }

    private boolean isInsideSmall(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + SMALL_SIZE && mouseY >= y && mouseY < y + SMALL_SIZE;
    }

    private boolean isInside(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + ROW_WIDTH && mouseY >= y && mouseY < y + ROW_HEIGHT;
    }

    /** Same bordered-gradient look as the other Statsboard screens. */
    private void drawStyledButton(DrawContext context, int x, int y, int w, int h, Text label, boolean hovered) {
        context.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF000000 | PANEL_BORDER);
        context.fillGradient(x, y, x + w, y + h,
                0xFF000000 | (hovered ? 0x362A50 : PANEL_TOP_COLOR),
                0xFF000000 | (hovered ? 0x221A34 : PANEL_BOTTOM_COLOR));

        int trim = hovered ? 0xFFE8C96A : 0xFF6E6280;
        context.fill(x, y, x + w, y + 1, trim);
        context.fill(x, y + h - 1, x + w, y + h, trim);
        context.fill(x, y, x + 1, y + h, trim);
        context.fill(x + w - 1, y, x + w, y + h, trim);

        context.drawCenteredTextWithShadow(this.textRenderer, label, x + w / 2, y + h / 2 - 4, 0xFFE8E0F0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
