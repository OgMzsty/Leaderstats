package com.statsboard;

import com.statsboard.gui.LeaderboardScreen;
import com.statsboard.network.StatsboardNetworking;
import com.statsboard.block.LeaderboardBlockEntityRenderer;
import com.statsboard.block.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Registered via the "client" entrypoint in fabric.mod.json, so this class
 * (and anything it touches, like LeaderboardScreen) is only ever loaded on
 * the physical client. Never reference it from server-only code.
 */
public class StatsboardModClient implements ClientModInitializer {
    private static KeyBinding openLeaderboardKey;

    @Override
    public void onInitializeClient() {
        BlockEntityRendererRegistry.register(ModBlockEntities.LEADERBOARD_BLOCK_ENTITY,
                LeaderboardBlockEntityRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(StatsboardNetworking.LEADERBOARD_CHANNEL,
                (client, handler, buf, responseSender) -> {
                    List<LeaderboardEntry> deaths = StatsboardNetworking.readEntries(buf);
                    List<LeaderboardEntry> advancements = StatsboardNetworking.readEntries(buf);

                    client.execute(() -> client.setScreen(new LeaderboardScreen(deaths, advancements)));
                });

        // Shows up in Options > Controls as a normal rebindable key. Unbound
        // by default (players choose their own key) to avoid clashing with
        // anything else they've already bound.
        openLeaderboardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.statsboard.open_leaderboard",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.statsboard"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openLeaderboardKey.wasPressed()) {
                if (client.player != null) {
                    // Silently runs the same command /showleaderboard does,
                    // so we reuse all the existing server-side logic and
                    // networking instead of duplicating it.
                    client.player.networkHandler.sendChatCommand("showleaderboard");
                }
            }
        });
    }
}

