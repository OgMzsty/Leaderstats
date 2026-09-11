package com.statsboard;

import com.statsboard.block.LeaderboardBlockEntityRenderer;
import com.statsboard.config.StatsboardConfig;
import com.statsboard.block.ModBlockEntities;
import com.statsboard.gui.LeaderboardConfigScreen;
import com.statsboard.gui.LeaderboardScreen;
import com.statsboard.gui.PlayerProfileScreen;
import com.statsboard.gui.ProfileRequests;
import net.minecraft.client.gui.screen.Screen;
import com.statsboard.network.StatsboardNetworking;
import com.statsboard.stat.StatKey;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
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
        // Touched at startup rather than on first GUI open, so config/statsboard.json
        // exists for the player to find before they go looking for it.
        StatsboardConfig.get();

        BlockEntityRendererRegistry.register(ModBlockEntities.LEADERBOARD_BLOCK_ENTITY,
                LeaderboardBlockEntityRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(StatsboardNetworking.BOARD_DATA,
                (client, handler, buf, responseSender) -> {
                    StatKey key = StatKey.read(buf);
                    boolean openScreen = buf.readBoolean();
                    List<LeaderboardEntry> entries = StatsboardNetworking.readEntries(buf);

                    client.execute(() -> {
                        if (openScreen) {
                            client.setScreen(new LeaderboardScreen(key, entries));
                        } else if (client.currentScreen instanceof LeaderboardScreen screen) {
                            // Anything else means the player closed or navigated
                            // away since asking; the reply is simply dropped.
                            screen.acceptBoard(key, entries);
                        }
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(StatsboardNetworking.PROFILE_DATA,
                (client, handler, buf, responseSender) -> {
                    UUID uuid = buf.readUuid();
                    String name = buf.readString();
                    String skinValue = null;
                    String skinSignature = null;
                    if (buf.readBoolean()) {
                        skinValue = buf.readString();
                        if (buf.readBoolean()) {
                            skinSignature = buf.readString();
                        }
                    }
                    List<ProfileEntry> entries = StatsboardNetworking.readProfile(buf);

                    final String finalName = name;
                    final String finalSkinValue = skinValue;
                    final String finalSkinSignature = skinSignature;
                    client.execute(() -> {
                        // A profile already open just takes the new data, so the
                        // periodic refresh does not rebuild the screen under the
                        // player and lose their scroll position.
                        if (client.currentScreen instanceof PlayerProfileScreen profile) {
                            profile.acceptProfile(uuid, entries);
                            return;
                        }
                        // Otherwise only open if this reply answers a click the
                        // player actually made, and the screen they clicked from
                        // is still up. A reply landing after they escaped out
                        // would otherwise pop a GUI over live gameplay.
                        Screen parent = ProfileRequests.consumeOpen(client, uuid);
                        if (parent != null) {
                            client.setScreen(new PlayerProfileScreen(parent, uuid,
                                    finalName, finalSkinValue, finalSkinSignature, entries));
                        }
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(StatsboardNetworking.OPEN_PICKER,
                (client, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    List<StatKey> columns = StatsboardNetworking.readKeys(buf);
                    client.execute(() -> client.setScreen(new LeaderboardConfigScreen(pos, columns)));
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
