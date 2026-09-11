package com.statsboard.block;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.statsboard.mixin.PlayerEntityAccessor;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatValueFormatter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Draws two full player-height character models side by side, each with
 * their name/stat line floating directly above their head. Text is
 * billboarded (always faces the camera); the player models are not, since
 * they should look like normal 3D characters standing in the world.
 */
public class LeaderboardBlockEntityRenderer implements BlockEntityRenderer<LeaderboardBlockEntity> {
    private static final double COLUMN_SPACING = 3.0;
    private static final double HEAD_TEXT_Y = 2.3;
    private static final double LABEL_TEXT_Y = 2.6;

    private final TextRenderer textRenderer;
    private final Map<UUID, OtherClientPlayerEntity> entityCache = new HashMap<>();

    public LeaderboardBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
        this.textRenderer = ctx.getTextRenderer();
    }

    @Override
    public void render(LeaderboardBlockEntity entity, float tickDelta, MatrixStack matrices,
                        VertexConsumerProvider vertexConsumers, int light, int overlay) {
        List<StatKey> columns = entity.getColumns();
        int count = columns.size();

        for (int i = 0; i < count; i++) {
            StatKey key = columns.get(i);
            LeaderboardBlockEntity.Column column = entity.getColumn(i);
            // Spread evenly about the block: one column centres on it, two sit
            // at +/-1.5, three at -3 / 0 / +3.
            double x = (i - (count - 1) / 2.0) * COLUMN_SPACING;

            drawPlayerModel(matrices, vertexConsumers, light, tickDelta,
                    column.uuid(), column.name(), column.skinValue(), column.skinSignature(), x, 0.0, 0.5);

            drawLine(matrices, vertexConsumers, light,
                    key.displayName().getString(),
                    x + 0.5, LABEL_TEXT_Y, 0.5, 0xFFD4AF37);
            drawLine(matrices, vertexConsumers, light,
                    column.name() + "  (" + StatValueFormatter.format(key, column.value()) + ")",
                    x + 0.5, HEAD_TEXT_Y, 0.5, 0xFFFFFFFF);
        }
    }

    /** The two models stand well outside the block's own box, so never cull on it. */
    @Override
    public boolean rendersOutsideBoundingBox(LeaderboardBlockEntity blockEntity) {
        return true;
    }

    private void drawLine(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                           String text, double x, double y, double z, int color) {
        matrices.push();
        matrices.translate(x, y, z);
        matrices.multiply(MinecraftClient.getInstance().gameRenderer.getCamera().getRotation());
        matrices.scale(-0.02f, -0.02f, 0.02f);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float width = textRenderer.getWidth(text);
        int backgroundColor = (int) (0.25f * 255f) << 24;

        textRenderer.draw(text, -width / 2f, 0, color, false, matrix, vertexConsumers,
                TextRenderer.TextLayerType.SEE_THROUGH, backgroundColor, light);

        matrices.pop();
    }

    private void drawPlayerModel(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                  float tickDelta, UUID uuid, String name, String skinValue,
                                  String skinSignature, double x, double y, double z) {
        if (uuid == null) {
            return;
        }
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        OtherClientPlayerEntity previewEntity = entityCache.computeIfAbsent(uuid, id -> {
            GameProfile profile = new GameProfile(id, name);
            if (skinValue != null && !skinValue.isEmpty()) {
                profile.getProperties().put("textures", new Property("textures", skinValue, skinSignature));
            }
            OtherClientPlayerEntity e = new OtherClientPlayerEntity(world, profile);
            e.getDataTracker().set(PlayerEntityAccessor.getPlayerModelParts(), (byte) 0x7F);
            return e;
        });

        matrices.push();
        matrices.translate(x, y, z);
        MinecraftClient.getInstance().getEntityRenderDispatcher().render(
                previewEntity, 0.0, 0.0, 0.0, 0f, tickDelta, matrices, vertexConsumers, light);
        matrices.pop();
    }
}
