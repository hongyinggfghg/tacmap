package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import dev.tacmap.xaerotacmap.client.annotate.AnnotationRenderer;
import dev.tacmap.xaerotacmap.client.annotate.AnnotationToolbar;
import dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore;
import dev.tacmap.xaerotacmap.client.annotate.DrawingController;
import dev.tacmap.xaerotacmap.client.annotate.DrawingController.Tool;
import dev.tacmap.xaerotacmap.client.gui.SquadScreen;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import dev.tacmap.xaerotacmap.config.TacMapConfig.DisplayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Forge-bus client events: HUD keybind polling, snapshot refreshing, the
 * world map screen overlay (waypoints + annotations + toolbar) and the
 * annotation drawing input handlers.
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
        while (KeyBinds.OPEN_SQUAD.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                mc.setScreen(new SquadScreen());
            }
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
        if (!mapOverlayBroken) {
            try {
                MapScreenRenderer.render(event);
            } catch (Throwable t) {
                mapOverlayBroken = true;
                XaeroTacMap.LOGGER.error("[TacMap] Failed to render the waypoint overlay; "
                        + "disabling it for this session.", t);
            }
        }
        // Annotation overlay has its own crash isolation so a failure here can
        // never take down the waypoint overlay (or vice versa).
        AnnotationRenderer.render(event);
    }

    // ------------------------------------------------------------ drawing input

    private static boolean onMapScreen(Screen screen) {
        return GuiMapHooks.init() && GuiMapHooks.isMapScreen(screen);
    }

    /**
     * Press state for the click-vs-drag resolution (v4.0.5): with a draw tool
     * active, the press is NOT swallowed anymore so Xaero still pans the map;
     * the actual vertex placement happens on RELEASE inside the same 5 px
     * click window Xaero itself uses (GuiMap.mouseReleased checks |dx| < 5
     * && |dy| < 5 before firing its own click logic).
     */
    private static boolean pressActive;
    private static double pressX;
    private static double pressY;
    private static int pressButton;

    @SubscribeEvent
    public static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!onMapScreen(screen)) {
            return;
        }
        double mx = event.getMouseX();
        double my = event.getMouseY();

        // 1) toolbar buttons
        int btn = AnnotationToolbar.hit(mx, my);
        if (btn >= 0) {
            applyToolbarAction(btn);
            event.setCanceled(true);
            return;
        }
        // 2) symbol palette
        var sym = AnnotationToolbar.hitPalette(mx, my);
        if (sym != null) {
            DrawingController.setPointSymbol(sym);
            DrawingController.setTool(Tool.POINT);
            event.setCanceled(true);
            return;
        }
        // 3) map area with a draw tool: defer everything to the release event
        if (AnnotationRenderer.lastView() != null) {
            Tool t = DrawingController.tool();
            if (DrawingController.isAwaitingLabel()) {
                // inline label editor swallows stray clicks while naming
                event.setCanceled(true);
                return;
            }
            if (t == Tool.NONE) {
                return; // vanilla Xaero interaction untouched
            }
            if (event.getButton() == 1 && !ClientMarkerStore.inSquad()) {
                return; // not drawing anyway - let Xaero show its menu
            }
            pressActive = true;
            pressX = mx;
            pressY = my;
            pressButton = event.getButton();
            if (event.getButton() == 1) {
                // right press: Xaero would fire its context menu on release;
                // route/polygon finishing is ours, so swallow the press
                // (Xaero never records it - no drag state to leak)
                event.setCanceled(true);
            }
            // left press intentionally NOT canceled: Xaero records isDown and
            // keeps panning while the user drags; release decides click vs drag
        }
    }

    @SubscribeEvent
    public static void onScreenMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        Screen screen = event.getScreen();
        if (!onMapScreen(screen)) {
            pressActive = false;
            return;
        }
        if (!pressActive) {
            return;
        }
        pressActive = false;
        if (event.getButton() != pressButton) {
            return;
        }
        double mx = event.getMouseX();
        double my = event.getMouseY();
        // same click window as Xaero's own GuiMap.m_6348_ logic (|dx|<5 && |dy|<5):
        // inside it = a click (place the vertex), outside = a drag (already panned)
        if (Math.abs(mx - pressX) >= 5.0D || Math.abs(my - pressY) >= 5.0D) {
            return;
        }
        if (AnnotationRenderer.lastView() == null) {
            return;
        }
        boolean consumed = DrawingController.onMouseClick(mx, my, event.getButton(),
                AnnotationRenderer.lastView(),
                AnnotationRenderer.lastGuiW(), AnnotationRenderer.lastGuiH(),
                AnnotationRenderer.lastDimension());
        if (consumed && event.getButton() == 1) {
            // LEFT releases must never be canceled: Xaero resets its drag
            // state inside mouseReleased, and skipping that leaves
            // leftMouseButton.isDown = true, which glues the map to the
            // cursor. Right releases were never registered by Xaero (press
            // was canceled), so canceling them is safe.
            event.setCanceled(true);
        }
    }

    private static void applyToolbarAction(int btn) {
        switch (btn) {
            case AnnotationToolbar.ACT_NONE_TOOL -> DrawingController.setTool(Tool.NONE);
            case AnnotationToolbar.ACT_POINT -> DrawingController.setTool(Tool.POINT);
            case AnnotationToolbar.ACT_ROUTE -> DrawingController.setTool(Tool.ROUTE);
            case AnnotationToolbar.ACT_POLYGON -> DrawingController.setTool(Tool.POLYGON);
            case AnnotationToolbar.ACT_CIRCLE -> DrawingController.setTool(Tool.CIRCLE);
            case AnnotationToolbar.ACT_ERASE -> DrawingController.setTool(Tool.ERASE);
            case AnnotationToolbar.ACT_PALETTE -> DrawingController.togglePalette();
            case AnnotationToolbar.ACT_SQUAD -> Minecraft.getInstance().setScreen(new SquadScreen());
            case AnnotationToolbar.ACT_EXPORT -> {
                if (ClientMarkerStore.inSquad()) {
                    Minecraft.getInstance().setScreen(new dev.tacmap.xaerotacmap.client.gui.ExportScreen());
                }
            }
            case AnnotationToolbar.ACT_IMPORT -> {
                if (ClientMarkerStore.inSquad()) {
                    Minecraft.getInstance().setScreen(new dev.tacmap.xaerotacmap.client.gui.ImportScreen());
                }
            }
            default -> {
            }
        }
    }

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!onMapScreen(event.getScreen())) {
            return;
        }
        // inline label editor + draw shortcuts swallow keys while active
        boolean consumed = DrawingController.onKeyPressed(event.getKeyCode(), event.getScanCode());
        if (consumed) {
            event.setCanceled(true);
            return;
        }
        // J opens the squad panel from the map too
        if (event.getKeyCode() == GLFW.GLFW_KEY_J) {
            Minecraft.getInstance().setScreen(new SquadScreen());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!onMapScreen(event.getScreen())) {
            return;
        }
        if (DrawingController.onCharTyped(event.getCodePoint())) {
            event.setCanceled(true);
        }
    }
}
