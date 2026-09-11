package com.statsboard.gui;

import com.statsboard.network.StatsboardNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Util;

import java.util.UUID;

/**
 * Tracks the one outstanding "open a profile" request, so a reply can only ever
 * open the screen the player actually asked for.
 *
 * <p>Without this the receiver would have to key off whatever screen happens to
 * be current when the reply lands. A round trip is easily 100ms on a real
 * server, which is plenty of time to press Escape twice: the reply would then
 * arrive with no screen open and pop a profile over live gameplay.
 *
 * <p>Client only, single threaded - everything here runs on the render thread.
 */
public final class ProfileRequests {
    /** Re-send an unanswered open after this long; the server drops requests inside its own window. */
    private static final long RETRY_MS = 300;

    private static UUID pendingUuid;
    private static Screen requester;
    private static long sentAtMs;

    private ProfileRequests() {
    }

    /** Asks for a profile to be opened, remembering who asked. */
    public static void requestOpen(Screen from, UUID uuid) {
        pendingUuid = uuid;
        requester = from;
        sentAtMs = Util.getMeasuringTimeMs();
        send(uuid);
    }

    /**
     * Re-sends an open that has gone unanswered. Two clicks inside the server's
     * 250ms window would otherwise leave the second silently dropped and the
     * first discarded as stale, so nothing opens at all.
     */
    public static void retryIfStale() {
        if (pendingUuid != null && Util.getMeasuringTimeMs() - sentAtMs >= RETRY_MS) {
            sentAtMs = Util.getMeasuringTimeMs();
            send(pendingUuid);
        }
    }

    /** Asks for fresh data for an already-open profile; never opens a screen. */
    public static void requestRefresh(UUID uuid) {
        send(uuid);
    }

    /**
     * The screen to use as the profile's parent, or null if this reply was not
     * asked for. Consumes the request either way, so a duplicate reply cannot
     * open a second screen.
     */
    public static Screen consumeOpen(MinecraftClient client, UUID uuid) {
        boolean wanted = uuid.equals(pendingUuid) && client.currentScreen == requester;
        Screen parent = wanted ? requester : null;
        if (uuid.equals(pendingUuid)) {
            clear();
        }
        return parent;
    }

    /** Called when a screen that may have asked goes away. */
    public static void clear() {
        pendingUuid = null;
        requester = null;
    }

    private static void send(UUID uuid) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(uuid);
        ClientPlayNetworking.send(StatsboardNetworking.REQUEST_PROFILE, buf);
    }
}
