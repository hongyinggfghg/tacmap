package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import dev.tacmap.xaerotacmap.config.TacMapConfig.DisplayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ScreenEvent;

/**
 * Forge-bus client events: HUD keybind polling, snapshot refreshing and the
 * world map screen overlay.
 */
@Mod.EventBusSubscriber(modid = XaeroTacMap.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private static boolean mapOverlayBroken = false;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (KeyBinds.TOGGLE_HUD.consumeClick()) {
            WaypointCache.toggleHotkey();
            feedbackToggle();
        }
        WaypointCache.onClientTick();
    }

    /** Action-bar feedback so the toggle key always gives a visible response. */
    private static void feedbackToggle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        boolean shown = WaypointCache.isHotkeyShown();
        DisplayMode mode = TacMapConfig.HUD_MODE.get();
        boolean nowVisible = switch (mode) {
            case HOTKEY -> shown;
            case ALWAYS, MAP_OPEN -> !shown;
        };
        mc.player.displayClientMessage(Component.translatable(nowVisible
                ? "xaerotacmap.hud.shown" : "xaerotacmap.hud.hidden"), true);
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (mapOverlayBroken) {
            return;
        }
        try {
            MapScreenRenderer.render(event);
        } catch (Throwable t) {
            mapOverlayBroken = true;
            XaeroTacMap.LOGGER.error("[TacMap] Failed to render the map overlay; disabling it for this session.", t);
        }
    }
}
