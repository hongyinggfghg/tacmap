package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.config.TacMapConfig;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Caches the nearest-waypoint snapshot so the HUD renders a stable, sorted list
 * while the (potentially expensive) collection runs at a fixed cadence -
 * default every 2 ticks = 10 refreshes per second, as requested.
 */
public final class WaypointCache {

    private static volatile List<WaypointEntry> snapshot = List.of();
    private static volatile String status = "not_in_world";
    private static int tickCounter = 0;
    private static boolean hotkeyShown = false;
    private static boolean loggedFirstData = false;
    private static boolean loggedRefreshError = false;

    private WaypointCache() {
    }

    /** Called once per client tick (END phase). */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        int interval = Math.max(1, TacMapConfig.UPDATE_INTERVAL_TICKS.get());
        if (++tickCounter >= interval) {
            tickCounter = 0;
            refresh(mc);
        }
    }

    private static void refresh(Minecraft mc) {
        try {
            refreshInner(mc);
        } catch (Throwable t) {
            // Config may briefly be unavailable during reloads - keep the last snapshot.
            if (!loggedRefreshError) {
                loggedRefreshError = true;
                dev.tacmap.xaerotacmap.XaeroTacMap.LOGGER.warn("[TacMap] HUD refresh failed", t);
            }
        }
    }

    private static void refreshInner(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            status = "not_in_world";
            snapshot = List.of();
            return;
        }
        if (!TacMapConfig.HUD_ENABLED.get()) {
            status = "disabled";
            snapshot = List.of();
            return;
        }
        if (!XaeroBridge.minimapPresent()) {
            status = "no_minimap";
            snapshot = List.of();
            return;
        }
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        List<WaypointEntry> collected = XaeroWaypointSource.collect(px, py, pz);
        status = XaeroWaypointSource.lastStatus;
        collected.sort((a, b) -> Double.compare(a.distance, b.distance));
        snapshot = List.copyOf(collected);
        if (!loggedFirstData && !collected.isEmpty()) {
            loggedFirstData = true;
            dev.tacmap.xaerotacmap.XaeroTacMap.LOGGER.info("[TacMap] Waypoint data OK: {} target(s) in the current set.", collected.size());
        }
    }

    /** Diagnostics: why the list is empty ("not_in_world", "no_minimap", "empty", "ok", ...). */
    public static String status() {
        return status;
    }

    /** The latest sorted snapshot (nearest first). Immutable. */
    public static List<WaypointEntry> snapshot() {
        return snapshot;
    }

    /** Toggled by the HUD keybind when display mode is HOTKEY. */
    public static void toggleHotkey() {
        hotkeyShown = !hotkeyShown;
    }

    public static boolean isHotkeyShown() {
        return hotkeyShown;
    }

    /** Whether Xaero waypoint data is reachable at all (for status display). */
    public static boolean isDataAvailable() {
        return XaeroBridge.minimapPresent();
    }
}
