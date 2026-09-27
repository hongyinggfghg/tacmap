package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import xaero.map.element.HoveredMapElementHolder;

import java.lang.reflect.Field;

/**
 * Integration with Xaero's World Map screen ({@code xaero.map.gui.GuiMap}).
 *
 * <p><b>This class must only be loaded when {@code XaeroBridge.worldmapPresent()}
 * is true.</b></p>
 *
 * <p>Public Xaero APIs are referenced directly (compile-only dependency); the few
 * private GuiMap fields are read through reflection with cached {@link Field}s:
 * <ul>
 *   <li>{@code viewed} - HoveredMapElementHolder of whatever map element the mouse
 *       is over; Xaero refreshes it every frame while rendering the map screen.</li>
 *   <li>{@code cameraX}, {@code cameraZ}, {@code scale} - the live map view state,
 *       updated every frame. Xaero renders elements in FRAMEBUFFER PIXEL space:
 *       GuiMap scales the matrix by 1/guiScale (line 969) and translates it to
 *       the pixel-space screen center (line 970), elements are placed at pixel
 *       offset (world - camera) * scale (lines 184-190 of
 *       MapElementRenderHandler, rounded to whole pixels), and that pixel space
 *       is divided back down by guiScale. So the gui-space position of a map
 *       point is guiSize / 2 + (world - camera) * scale / guiScale - the
 *       division by the window GUI scale is REQUIRED; {@code scale} is counted
 *       in physical framebuffer pixels per block, not gui units per block.</li>
 *   <li>{@code mouseDownPosX}, {@code cameraDestination}, {@code cameraDestinationAnimX},
 *       {@code cameraDestinationAnimZ} - user interaction state used to detect when the
 *       map camera is being moved (drag, release inertia or jump-to-waypoint
 *       animation). While the camera moves, the element under the stationary cursor
 *       keeps changing, so hover-based overlays must lock their target until the
 *       camera comes to rest.</li>
 * </ul></p>
 *
 * <p>{@link xaero.map.mods.SupportMods#xaeroMinimap#getDimDiv()} (public API) provides
 * the dimension coordinate division Xaero applies on the current map (1.0 for 1:1
 * dimensions, 8.0 in the Nether), so world positions can be converted into map space.</p>
 */
public final class GuiMapHooks {

    /** One hovered waypoint on the world map, fully resolved to plain data. */
    public static final class HoverInfo {
        public final String name;
        public final String symbol;
        public final int colorRgb;
        public final int x;
        public final int y;
        public final int z;
        public final boolean yIncluded;
        /** Map-space coordinates Xaero uses to place the waypoint icon (dimension scaled). */
        public final double renderX;
        public final double renderZ;

        HoverInfo(String name, String symbol, int colorRgb, int x, int y, int z,
                  boolean yIncluded, double renderX, double renderZ) {
            this.name = name;
            this.symbol = symbol;
            this.colorRgb = colorRgb;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yIncluded = yIncluded;
            this.renderX = renderX;
            this.renderZ = renderZ;
        }
    }

    /**
     * Live map view state: camera position (dimension-scaled world coords),
     * Xaero's pixel-per-block scale and the window GUI scale.
     *
     * <p>Coordinate spaces (verified against decompiled xaeroworldmap 1.46.0,
     * GuiMap.render lines 969-970 and 1354 plus MapElementRenderHandler lines
     * 96 and 184-190): Xaero renders map elements inside a matrix that is
     * translated to the FRAMEBUFFER-pixel screen center and scaled by
     * {@code 1/guiScale}; each element is translated by the PIXEL offset
     * {@code (worldPos - camera) * scale} (icons additionally rounded to whole
     * pixels), and the whole pixel space is brought back to gui units by the
     * {@code 1/guiScale} matrix factor. The resulting gui-space position of a
     * map point is therefore:</p>
     *
     * <pre>gui = guiSize / 2 + (world - camera) * scale / guiScale</pre>
     *
     * <p>Missing the {@code / guiScale} makes the line static while
     * cursor-anchored zooming (Xaero keeps {@code (world - camera) * scale}
     * constant for the anchored point) and guiScale-times too fast while
     * dragging - which is exactly the "line frozen on screen" symptom.</p>
     */
    public static final class ViewState {
        public final double cameraX;
        public final double cameraZ;
        public final double scale;
        /** Window GUI scale factor (vanilla video settings scale, always >= 1). */
        public final double guiScale;

        ViewState(double cameraX, double cameraZ, double scale, double guiScale) {
            this.cameraX = cameraX;
            this.cameraZ = cameraZ;
            this.scale = scale;
            this.guiScale = guiScale > 0.0D && !Double.isNaN(guiScale) ? guiScale : 1.0D;
        }

        /** World X -> gui-screen X, smooth (used for the player arrow). */
        public double toScreenX(double worldX, int guiWidth) {
            return guiWidth / 2.0D + (worldX - cameraX) * scale / guiScale;
        }

        /** World Z -> gui-screen Y, smooth (map is top-down, Z maps to screen Y). */
        public double toScreenY(double worldZ, int guiHeight) {
            return guiHeight / 2.0D + (worldZ - cameraZ) * scale / guiScale;
        }

        /**
         * World X -> gui-screen X with Xaero's integer-PIXEL snapping. Waypoint
         * icons are placed at {@code Math.round(pixelOffset)}
         * (MapElementRenderHandler line 186), so the tactical line tip must snap
         * the pixel offset the same way to stay glued to the icon.
         */
        public double toScreenXSnap(double worldX, int guiWidth) {
            return guiWidth / 2.0D + Math.round((worldX - cameraX) * scale) / guiScale;
        }

        /** World Z -> gui-screen Y with Xaero's integer-PIXEL snapping. */
        public double toScreenYSnap(double worldZ, int guiHeight) {
            return guiHeight / 2.0D + Math.round((worldZ - cameraZ) * scale) / guiScale;
        }
    }

    private static boolean initialized = false;
    private static boolean usable = false;
    private static Class<?> guiMapClass;
    private static Field fViewed;
    private static Field fCameraX;
    private static Field fCameraZ;
    private static Field fScale;
    private static Field fMouseDownPosX;
    private static Field fCameraDestination;
    private static Field fCamDestAnimX;
    private static Field fCamDestAnimZ;
    private static boolean freezeHooksReady = false;
    private static boolean warned = false;

    private GuiMapHooks() {
    }

    /**
     * Initializes the reflective hooks once. Safe to call every frame.
     *
     * @return true when the world map screen can be introspected.
     */
    public static synchronized boolean init() {
        if (initialized) {
            return usable;
        }
        initialized = true;
        try {
            guiMapClass = Class.forName("xaero.map.gui.GuiMap");
            fViewed = guiMapClass.getDeclaredField("viewed");
            fViewed.setAccessible(true);
            fCameraX = guiMapClass.getDeclaredField("cameraX");
            fCameraX.setAccessible(true);
            fCameraZ = guiMapClass.getDeclaredField("cameraZ");
            fCameraZ.setAccessible(true);
            fScale = guiMapClass.getDeclaredField("scale");
            fScale.setAccessible(true);
            usable = true;
            XaeroTacMap.LOGGER.info("[TacMap] Xaero World Map hooks initialized.");
        } catch (Throwable t) {
            usable = false;
            XaeroTacMap.LOGGER.warn("[TacMap] Could not hook into Xaero's World Map GuiMap; "
                    + "map-screen features are disabled. Installed World Map version may be incompatible.", t);
            return usable;
        }
        try {
            fMouseDownPosX = guiMapClass.getDeclaredField("mouseDownPosX");
            fMouseDownPosX.setAccessible(true);
            fCameraDestination = guiMapClass.getDeclaredField("cameraDestination");
            fCameraDestination.setAccessible(true);
            fCamDestAnimX = guiMapClass.getDeclaredField("cameraDestinationAnimX");
            fCamDestAnimX.setAccessible(true);
            fCamDestAnimZ = guiMapClass.getDeclaredField("cameraDestinationAnimZ");
            fCamDestAnimZ.setAccessible(true);
            freezeHooksReady = true;
        } catch (Throwable t) {
            freezeHooksReady = false;
            XaeroTacMap.LOGGER.debug("[TacMap] Drag-freeze hooks unavailable; the tactical line "
                    + "will follow the live hover target instead of locking while the map moves.", t);
        }
        return usable;
    }

    /** True when the given screen is the Xaero world map screen. */
    public static boolean isMapScreen(Screen screen) {
        return usable && guiMapClass != null && guiMapClass.isInstance(screen);
    }

    /**
     * Reads the live map view state (camera + pixel scale + gui scale), or null
     * when unavailable. The GUI scale comes from the vanilla window (public API,
     * no reflection) and is required to convert Xaero's pixel-space offsets into
     * gui-screen coordinates - see {@link ViewState}.
     */
    public static ViewState getViewState(Screen screen) {
        if (!usable) {
            return null;
        }
        double guiScale = 1.0D;
        try {
            guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        } catch (Throwable ignored) {
            // leave 1.0 - only degrades snapping precision, never correctness
        }
        try {
            double camX = fCameraX.getDouble(screen);
            double camZ = fCameraZ.getDouble(screen);
            double scale = fScale.getDouble(screen);
            if (scale <= 0.0D || Double.isNaN(scale)) {
                return null;
            }
            return new ViewState(camX, camZ, scale, guiScale);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /**
     * True while the map camera is being moved by user interaction: an active
     * left-button drag, a pending jump-to-waypoint target, or the decaying drag
     * inertia / jump animation. While any of these is active, Xaero keeps
     * re-evaluating which map element sits under the stationary cursor, so
     * hover-driven overlays must lock their target until the camera rests.
     *
     * <p>Primary detection reads Xaero's interaction-state fields; if those are
     * unavailable (or silently fail), it falls back to comparing the camera
     * values against the previous frame, which works regardless of field layout.</p>
     */
    public static boolean isMapMoving(Screen screen) {
        boolean moving = false;
        if (usable && freezeHooksReady) {
            try {
                if (fMouseDownPosX.getInt(screen) != -1) {
                    moving = true;
                } else if (fCameraDestination.get(screen) != null) {
                    moving = true;
                } else if (fCamDestAnimX.get(screen) != null || fCamDestAnimZ.get(screen) != null) {
                    moving = true;
                }
            } catch (Throwable t) {
                warnOnce(t);
                moving = false;
            }
        }
        // The delta check also catches movement the interaction fields miss
        // (e.g. release inertia between field updates), so always consult it
        // unless we already know the map is moving.
        return moving || isCameraChanging(screen);
    }

    private static double deltaPrevCamX = Double.NaN;
    private static double deltaPrevCamZ;
    private static double deltaPrevScale;

    /**
     * Frame-to-frame camera comparison. True on any frame where the camera or
     * scale differs from the previous rendered frame - i.e. the map is sliding,
     * animating or zooming right now. Used as the robust fallback for
     * {@link #isMapMoving(Screen)}.
     */
    private static boolean isCameraChanging(Screen screen) {
        ViewState v = getViewState(screen);
        if (v == null) {
            return false;
        }
        boolean changed;
        if (lastScreenCheck != screen || Double.isNaN(deltaPrevCamX)) {
            // First frame on this screen: establish the baseline, no motion.
            changed = false;
        } else {
            changed = Math.abs(v.cameraX - deltaPrevCamX) > 1.0E-9D
                    || Math.abs(v.cameraZ - deltaPrevCamZ) > 1.0E-9D
                    || Math.abs(v.scale - deltaPrevScale) > 1.0E-12D;
        }
        lastScreenCheck = screen;
        deltaPrevCamX = v.cameraX;
        deltaPrevCamZ = v.cameraZ;
        deltaPrevScale = v.scale;
        return changed;
    }

    private static Screen lastScreenCheck;

    /** True while a left-button map drag is actively in progress (mouse held down on empty map). */
    public static boolean isDragging(Screen screen) {
        if (!usable || !freezeHooksReady) {
            return false;
        }
        try {
            return fMouseDownPosX.getInt(screen) != -1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clears the camera-delta tracking state (call when the screen instance changes). */
    public static void resetDeltaTracking() {
        deltaPrevCamX = Double.NaN;
        lastScreenCheck = null;
    }

    /**
     * Dimension coordinate division Xaero applies on the current map view
     * (1.0 for 1:1 dimensions, 8.0 when viewing the Nether). World positions must
     * be divided by this value before being projected with {@link ViewState}.
     * Falls back to 1.0 when the minimap support bridge is unavailable.
     */
    public static double getPlayerDimDiv() {
        try {
            xaero.map.mods.SupportXaeroMinimap support = xaero.map.mods.SupportMods.xaeroMinimap;
            if (support != null) {
                double d = support.getDimDiv();
                if (d > 0.0D && !Double.isNaN(d) && !Double.isInfinite(d)) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return 1.0D;
    }

    /**
     * Returns the waypoint currently hovered by the mouse on the world map, or
     * null when the mouse is over nothing (or over a non-waypoint element).
     */
    public static HoverInfo getHoveredWaypoint(Screen screen) {
        if (!usable) {
            return null;
        }
        try {
            Object viewed = fViewed.get(screen);
            if (!(viewed instanceof HoveredMapElementHolder<?, ?> holder)) {
                return null;
            }
            Object element = holder.getElement();
            if (!(element instanceof xaero.map.mods.gui.Waypoint mapWaypoint)) {
                return null;
            }
            return resolve(mapWaypoint);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /**
     * Builds a HoverInfo from the world map's waypoint element. Prefers the
     * original Xaero Minimap waypoint (exact real coordinates and color), and
     * falls back to the wrapper's own data for third-party waypoints.
     */
    private static HoverInfo resolve(xaero.map.mods.gui.Waypoint mapWaypoint) {
        Object original = mapWaypoint.getOriginal();
        double renderX = mapWaypoint.getRenderX();
        double renderZ = mapWaypoint.getRenderZ();
        if (original instanceof xaero.common.minimap.waypoints.Waypoint wp) {
            String name = wp.getLocalizedName();
            if (name == null || name.isEmpty()) {
                name = wp.getName();
            }
            xaero.hud.minimap.waypoint.WaypointColor color = wp.getWaypointColor();
            int rgb = color != null ? (color.getHex() & 0xFFFFFF) : 0xFFFFFF;
            String symbol = wp.getSymbol();
            return new HoverInfo(
                    name == null ? "?" : name,
                    symbol == null ? "" : symbol,
                    rgb,
                    wp.getX(), wp.getY(), wp.getZ(),
                    wp.isYIncluded(),
                    renderX, renderZ);
        }
        // Fallback: wrapper-only data (still fully usable).
        int rgb = mapWaypoint.getColor() & 0xFFFFFF;
        String symbol = mapWaypoint.getSymbol();
        return new HoverInfo(
                safe(mapWaypoint.getName()),
                symbol == null ? "" : symbol,
                rgb,
                mapWaypoint.getX(), mapWaypoint.getY(), mapWaypoint.getZ(),
                mapWaypoint.isyIncluded(),
                renderX, renderZ);
    }

    private static String safe(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }

    private static void warnOnce(Throwable t) {
        if (!warned) {
            warned = true;
            XaeroTacMap.LOGGER.warn("[TacMap] World Map hook read failed; map features disabled this session.", t);
        }
    }
}
