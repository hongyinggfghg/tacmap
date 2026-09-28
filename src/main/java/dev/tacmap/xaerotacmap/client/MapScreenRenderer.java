package dev.tacmap.xaerotacmap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;

/**
 * World map screen overlay (Xaero's World Map, M key):
 *
 * <ul>
 *   <li>Tactical panel next to the hovered waypoint with precise distance,
 *       absolute bearing, 8-way direction, height difference and coordinates.
 *       The panel automatically avoids covering the player arrow.</li>
 *   <li>ATAK-style dashed bearing line from the player to the hovered waypoint,
 *       clipped to the viewport, with an arrow head and an optional midpoint
 *       distance/bearing label. Both endpoints are recomputed EVERY frame from
 *       the live map camera, so the line rides the map during drags and zooms
 *       exactly like Xaero's own waypoint icons.</li>
 *   <li>Live diagnostic bar (config "debugBar", default OFF): shows the build
 *       tag, the live camera values the overlay reads, and a hover calibration
 *       check. This exists because the line math was verified against Xaero's
 *       decompiled rendering code - if the line ever appears frozen while the
 *       map moves, the bar tells us within one glance whether the overlay is
 *       actually receiving live camera values at runtime.</li>
 * </ul>
 *
 * <p>Coordinate conversion (verified against decompiled xaeroworldmap 1.46.0,
 * GuiMap.render lines 969-970/1354 + MapElementRenderHandler lines 96 and
 * 184-190): Xaero renders map elements in FRAMEBUFFER-PIXEL space - pixel
 * offset {@code (worldPos / dimDiv - camera) * scale} from the pixel-space
 * screen center, with waypoint icons rounded to whole pixels - and the whole
 * pixel space is divided back down by the window GUI scale. So the gui-space
 * position is {@code guiSize / 2 + pixelOffset / guiScale}; cameraX/cameraZ/
 * scale are read live from GuiMap every frame and guiScale comes from the
 * vanilla window. Skipping the {@code / guiScale} makes the line hold still
 * during cursor-anchored zooming (Xaero keeps the pixel offset of the anchored
 * point constant) and move guiScale-times too fast while dragging - the exact
 * "frozen line" bug reported against v2.1 and earlier. Waypoint render
 * positions from xaero.map.mods.gui.Waypoint#getRenderX are already in camera
 * space (dimension-scaled), so they are used as-is.</p>
 */
public final class MapScreenRenderer {

    private static final int BG = 0xC60D1114;
    private static final int BORDER = 0xFF2FA8B8;
    private static final int LABEL = 0xFF7ADBDF;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int TEXT_DIM = 0xFF9FB4B8;
    private static final int CHIP_BG = 0xB40D1114;
    private static final int DBG_BG = 0x900D1114;
    private static final int DBG_OK = 0xFF7FE28A;
    private static final int DBG_BAD = 0xFFE28A8A;
    /** Amber used for the yaw (F3 facing) readout (v4.0.7 dual degree mode). */
    private static final int YAW = 0xFFE0B050;

    /**
     * Screen-space radius Xaero's player arrow occupies around the line start.
     * The midpoint distance/bearing chip keeps itself outside this circle so the
     * arrow never visually overlaps the info text; the hover panel also refuses
     * placements that would cover this circle (bug #2 fix).
     */
    private static final double PLAYER_ARROW_CLEAR_R = 22.0D;

    /** Build tag shown by the diagnostic bar - proves which jar is actually loaded. */
    private static final String BUILD_TAG = "TacMap v4.0.12";

    /** Map screen the overlay last rendered onto (for hover-lock state reset). */
    private static Screen lastScreen;

    /**
     * Last waypoint resolved while the camera was at rest. While the user drags
     * the map (or the camera slides via drag inertia / jump animation), the map
     * keeps moving under the stationary cursor, so Xaero's hovered element
     * changes from frame to frame. The line keeps targeting THIS waypoint until
     * the camera comes to rest, while its screen position keeps being recomputed
     * from the live camera every frame - so the line stays glued to the waypoint
     * icon while the map moves.
     */
    private static GuiMapHooks.HoverInfo lockedHover;

    // --- diagnostic bar state ------------------------------------------------
    private static double dbgPrevCamX = Double.NaN;
    private static double dbgPrevCamZ;
    private static double dbgPrevScale;
    private static long dbgLastChangeMs;
    private static boolean dbgEverChanged;

    private MapScreenRenderer() {
    }

    /** Entry point, called from {@code ScreenEvent.Render.Post}. */
    public static void render(ScreenEvent.Render.Post event) {
        if (!XaeroBridge.worldmapPresent() || !GuiMapHooks.init()) {
            return;
        }
        Screen screen = event.getScreen();
        if (!GuiMapHooks.isMapScreen(screen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        GuiMapHooks.ViewState view = GuiMapHooks.getViewState(screen);
        if (view == null) {
            return;
        }

        // --- hover target resolution (identity lock while the camera moves) --
        // Xaero re-evaluates the hovered element every frame, even while the map
        // is being dragged. During a drag the element under the stationary cursor
        // keeps changing; the line keeps its target from before the drag instead
        // of hopping between waypoints. Only the TARGET is locked - both line
        // endpoints are still recomputed from the live camera below.
        GuiMapHooks.HoverInfo liveHover = GuiMapHooks.getHoveredWaypoint(screen);
        if (lastScreen != screen) {
            lastScreen = screen;
            lockedHover = null;
            dbgEverChanged = false;
            dbgPrevCamX = Double.NaN;
            GuiMapHooks.resetDeltaTracking();
        }
        boolean mapMoving = GuiMapHooks.isMapMoving(screen);
        if (!mapMoving) {
            lockedHover = liveHover;
        }
        GuiMapHooks.HoverInfo hovered = mapMoving ? lockedHover : liveHover;
        boolean dragging = GuiMapHooks.isDragging(screen);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        int decimals = TacMapConfig.DECIMALS.get();
        int guiW = screen.width;
        int guiH = screen.height;

        // Player arrow position in gui-screen space. Uses the same interpolation
        // and dimension division as Xaero's own arrow rendering (verified against
        // GuiMap: EntityUtil.getEntityX(player, partialTicks) / playerDimDiv).
        float partialTick = event.getPartialTick();
        double dimDiv = GuiMapHooks.getPlayerDimDiv();
        double rawPx = mc.player.getX(partialTick);
        double rawPz = mc.player.getZ(partialTick);
        double px = rawPx / dimDiv;
        double pz = rawPz / dimDiv;
        double arrowX = view.toScreenX(px, guiW);
        double arrowY = view.toScreenY(pz, guiH);

        if (hovered != null && TacMapConfig.MAP_TACTICAL_LINE.get()) {
            // Xaero snaps waypoint ICONS to integer framebuffer pixels
            // (MapElementRenderHandler translates by Math.round((render - camera) * scale)
            // in pixel space, then divides the pixel space down by the GUI scale),
            // so the target endpoint snaps the pixel offset the same way
            // (toScreenXSnap) - otherwise the line tip drifts against the icon.
            double tx = view.toScreenXSnap(hovered.renderX, guiW);
            double ty = view.toScreenYSnap(hovered.renderZ, guiH);
            if (!Double.isNaN(tx) && !Double.isInfinite(tx)
                    && !Double.isNaN(ty) && !Double.isInfinite(ty)) {
                double dist = hovered.yIncluded
                        ? BearingMath.distance3D(hovered.x - rawPx,
                                hovered.y - mc.player.getY(),
                                hovered.z - rawPz)
                        : BearingMath.distance2D(hovered.x - rawPx, hovered.z - rawPz);
                double brg = BearingMath.bearingDeg(rawPx, rawPz, hovered.x, hovered.z);
                double yaw = BearingMath.yawDeg(rawPx, rawPz, hovered.x, hovered.z);
                drawTacticalLine(gg, arrowX, arrowY, tx, ty, guiW, guiH,
                        BearingMath.opaque(hovered.colorRgb),
                        dist, brg, yaw, decimals, font,
                        TacMapConfig.MAP_LINE_MID_LABEL.get());
            }
        }

        // While a drag is actively in progress the cursor is busy moving the map;
        // hide the mouse-attached panel the same way Xaero hides its own tooltips.
        if (hovered != null && TacMapConfig.MAP_HOVER_PANEL.get() && !dragging) {
            drawHoverPanel(gg, font, event.getMouseX(), event.getMouseY(), guiW, guiH,
                    hovered, decimals, arrowX, arrowY);
        }

        if (TacMapConfig.MAP_DEBUG_BAR.get()) {
            // The calibration check is only meaningful while the camera is at rest:
            // during a drag the locked target is usually NOT under the cursor.
            drawDebugBar(gg, font, guiW, guiH, view, event.getMouseX(), event.getMouseY(),
                    mapMoving ? null : hovered, dimDiv, mapMoving);
        }
    }

    // ==================================================================
    // Diagnostic bar
    // ==================================================================

    /**
     * Bottom-left bar with the build tag and the live camera values the overlay
     * reads. How to read it while testing:
     * <ul>
     *   <li>{@code cam/s} values must change while you drag or zoom the map.
     *       If they never change, the overlay is not receiving live map state
     *       and that is exactly what a screenshot of the bar will show us.</li>
     *   <li>{@code cal d=..} appears while hovering a waypoint: distance from
     *       the cursor to the icon position predicted by the conversion formula.
     *       It must stay small (within Xaero's hover box, roughly 40 px). A huge
     *       value while the cursor visibly sits on the icon means the runtime
     *       camera values do not match the map on screen.</li>
     * </ul>
     */
    private static void drawDebugBar(GuiGraphics gg, Font font, int guiW, int guiH,
                                     GuiMapHooks.ViewState view, double mouseX, double mouseY,
                                     GuiMapHooks.HoverInfo hovered, double dimDiv, boolean mapMoving) {
        long now = System.currentTimeMillis();
        boolean changed = !Double.isNaN(dbgPrevCamX)
                && (Math.abs(view.cameraX - dbgPrevCamX) > 1.0E-9D
                        || Math.abs(view.cameraZ - dbgPrevCamZ) > 1.0E-9D
                        || Math.abs(view.scale - dbgPrevScale) > 1.0E-12D);
        if (changed) {
            dbgLastChangeMs = now;
            dbgEverChanged = true;
        }
        dbgPrevCamX = view.cameraX;
        dbgPrevCamZ = view.cameraZ;
        dbgPrevScale = view.scale;
        boolean live = changed || (dbgEverChanged && now - dbgLastChangeMs < 1500L);

        // colored segments: [text, argb]
        String stateText;
        int stateColor;
        if (mapMoving) {
            stateText = "moving";
            stateColor = DBG_OK;
        } else if (live) {
            stateText = "cam-LIVE";
            stateColor = DBG_OK;
        } else {
            stateText = "cam-idle";
            stateColor = TEXT_DIM;
        }
        String seg0 = BUILD_TAG + " | cam " + BearingMath.fmt(view.cameraX, 1)
                + ',' + BearingMath.fmt(view.cameraZ, 1)
                + " | s " + BearingMath.fmt(view.scale, 3)
                + " | gs " + BearingMath.fmt(view.guiScale, 0)
                + " | dd " + BearingMath.fmt(dimDiv, 1) + " | ";
        String seg2;
        int seg2Color = TEXT_DIM;
        if (hovered != null) {
            // Same conversion as the tactical line itself, so "cal" tells us how
            // far the cursor is from the icon position our math predicts. The
            // delta is scaled up to framebuffer pixels because Xaero's hover box
            // is defined in pixel space; it must stay within roughly the icon's
            // hover box (a few dozen px). A huge value while the cursor visibly
            // sits on the icon would mean the runtime camera values do not match
            // the map on screen.
            double expectedX = view.toScreenXSnap(hovered.renderX, guiW);
            double expectedY = view.toScreenYSnap(hovered.renderZ, guiH);
            double ddx = (mouseX - expectedX) * view.guiScale;
            double ddy = (mouseY - expectedY) * view.guiScale;
            boolean ok = ddx >= -40.0D && ddx <= 40.0D && ddy >= -60.0D && ddy <= 30.0D;
            seg2 = " | cal d=" + (int) Math.round(ddx) + ',' + (int) Math.round(ddy)
                    + (ok ? " OK" : " FAIL");
            seg2Color = ok ? DBG_OK : DBG_BAD;
        } else {
            seg2 = " | cal --";
        }

        int w = font.width(seg0) + font.width(stateText) + font.width(seg2);
        int lx = 4;
        int ly = guiH - 12;
        gg.fill(lx - 2, ly - 2, lx + w + 2, ly + 10, DBG_BG);
        int tx = lx;
        gg.drawString(font, seg0, tx, ly, TEXT, true);
        tx += font.width(seg0);
        gg.drawString(font, stateText, tx, ly, stateColor, true);
        tx += font.width(stateText);
        gg.drawString(font, seg2, tx, ly, seg2Color, true);
    }

    // ==================================================================
    // Tactical dashed line
    // ==================================================================

    private static void drawTacticalLine(GuiGraphics gg, double sx, double sy, double tx, double ty,
                                         int guiW, int guiH, int argb,
                                         double distance, double bearing, double yaw, int decimals,
                                         Font font, boolean midLabel) {
        double dx = tx - sx;
        double dy = ty - sy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 6.0D) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;

        double t0 = 0.0D;
        double t1 = 1.0D;
        if (!clipToScreen(sx, sy, dx, dy, guiW, guiH, out)) {
            return;
        }
        t0 = Math.max(t0, out[0]);
        t1 = Math.min(t1, out[1]);
        if (t1 - t0 < 0.02D) {
            return;
        }

        double x0 = sx + dx * t0;
        double y0 = sy + dy * t0;
        double x1 = sx + dx * t1;
        double y1 = sy + dy * t1;
        double segLen = len * (t1 - t0);

        boolean targetOnScreen = t1 >= 0.999D;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        PoseStack.Pose pose = gg.pose().last();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        int abgr = BearingMath.argbToAbgr(argb);
        double halfW = 0.9D;
        double px = -uy;
        double py = ux;

        double dash = 9.0D;
        double gap = 6.0D;
        double pattern = dash + gap;
        double d = 0.0D;
        while (d < segLen) {
            double dEnd = Math.min(d + dash, segLen);
            double ax = x0 + ux * d;
            double ay = y0 + uy * d;
            double bx = x0 + ux * dEnd;
            double by = y0 + uy * dEnd;
            // quad corners (two triangles)
            vertex(bb, pose, ax + px * halfW, ay + py * halfW, abgr);
            vertex(bb, pose, bx + px * halfW, by + py * halfW, abgr);
            vertex(bb, pose, bx - px * halfW, by - py * halfW, abgr);
            vertex(bb, pose, ax + px * halfW, ay + py * halfW, abgr);
            vertex(bb, pose, bx - px * halfW, by - py * halfW, abgr);
            vertex(bb, pose, ax - px * halfW, ay - py * halfW, abgr);
            d += pattern;
        }

        // arrow head at the waypoint end
        if (targetOnScreen) {
            double tipX = tx;
            double tipY = ty;
            double b1x = tx - ux * 9.0D + px * 5.0D;
            double b1y = ty - uy * 9.0D + py * 5.0D;
            double b2x = tx - ux * 9.0D - px * 5.0D;
            double b2y = ty - uy * 9.0D - py * 5.0D;
            vertex(bb, pose, tipX, tipY, abgr);
            vertex(bb, pose, b1x, b1y, abgr);
            vertex(bb, pose, b2x, b2y, abgr);
        }

        BufferUploader.drawWithShader(bb.end());
        RenderSystem.enableDepthTest();

        // midpoint label
        if (midLabel) {
            // v4.0.7: both degree modes side by side, e.g. "800m C215.0° Y-145.0°"
            String cmpS = Component.translatable("xaerotacmap.brg.cmp_s").getString();
            String yawS = Component.translatable("xaerotacmap.brg.yaw_s").getString();
            String label = BearingMath.fmt(distance, decimals) + "m  "
                    + cmpS + BearingMath.fmt(bearing, decimals) + "\u00B0  "
                    + yawS + BearingMath.fmt(yaw, decimals) + "\u00B0";
            double chipHalfW = font.width(label) / 2.0D + 6.0D;
            double perpDist = 12.0D;
            // Keep the chip clear of BOTH anchors (all distances measured along
            // the visible/clipped segment):
            // - line start: Xaero's player arrow occupies roughly a
            //   PLAYER_ARROW_CLEAR_R circle around it; the chip is offset
            //   perpDist sideways, so the required along-line clearance is the
            //   circle chord + the chip half width. Without this the chip sits
            //   on top of the player arrow on short lines.
            // - line end: the waypoint icon plus our own arrow head.
            // If the segment is too short to satisfy both, the chip is skipped
            // entirely - the hover panel already shows the same numbers.
            boolean playerOnScreen = sx >= -8.0D && sx <= guiW + 8.0D
                    && sy >= -8.0D && sy <= guiH + 8.0D;
            double startClear = 8.0D;
            if (playerOnScreen) {
                double radial = perpDist < PLAYER_ARROW_CLEAR_R
                        ? Math.sqrt(PLAYER_ARROW_CLEAR_R * PLAYER_ARROW_CLEAR_R - perpDist * perpDist)
                        : 0.0D;
                startClear = radial + chipHalfW + 4.0D;
            }
            double endClear = targetOnScreen ? chipHalfW + 14.0D : 8.0D;
            if (segLen - startClear - endClear >= 24.0D) {
                double along = Math.max(startClear, Math.min(segLen * 0.5D, segLen - endClear));
                double mx = x0 + ux * along + px * perpDist;
                double my = y0 + uy * along + py * perpDist;
                int w = font.width(label);
                int lx = (int) mx - w / 2;
                int ly = (int) my - 5;
                lx = Math.max(2, Math.min(guiW - w - 2, lx));
                ly = Math.max(2, Math.min(guiH - 12, ly));
                gg.fill(lx - 3, ly - 2, lx + w + 3, ly + 10, CHIP_BG);
                gg.drawString(font, label, lx, ly, TEXT, true);
            }
        }
    }

    private static void vertex(BufferBuilder bb, PoseStack.Pose pose, double x, double y, int abgr) {
        bb.vertex(pose.pose(), (float) x, (float) y, 0.0F)
                .color((abgr >> 16) & 0xFF, (abgr >> 8) & 0xFF, abgr & 0xFF, (abgr >> 24) & 0xFF)
                .endVertex();
    }

    /** Simple Liang-Barsky clip of p + t*d against [0,w]x[0,h]. Returns t range in out[0..1]. */
    private static final double[] out = new double[2];

    private static boolean clipToScreen(double px, double py, double dx, double dy,
                                        int w, int h, double[] outT) {
        double t0 = 0.0D;
        double t1 = 1.0D;
        double[] edges = {-dx, dx, -dy, dy};
        double[] refs = {px, w - px, py, h - py};
        for (int i = 0; i < 4; i++) {
            double p = edges[i];
            double q = refs[i];
            if (Math.abs(p) < 1.0E-9D) {
                if (q < 0.0D) {
                    return false;
                }
            } else {
                double r = q / p;
                if (p < 0.0D) {
                    t0 = Math.max(t0, r);
                } else {
                    t1 = Math.min(t1, r);
                }
            }
        }
        outT[0] = t0;
        outT[1] = t1;
        return t0 <= t1;
    }

    // ==================================================================
    // Hover panel
    // ==================================================================

    private static void drawHoverPanel(GuiGraphics gg, Font font, double mouseX, double mouseY,
                                       int guiW, int guiH, GuiMapHooks.HoverInfo h, int decimals,
                                       double arrowX, double arrowY) {
        String symbol = h.symbol == null ? "" : h.symbol;
        String name = h.name;
        if (font.width(name) > 150) {
            name = TacHud.trim(font, name, 150);
        }

        String symText = symbol.isEmpty() ? "" : symbol + " ";

        // Distances are computed against the live player position by the caller;
        // here we recompute locally to keep the panel self-contained.
        Minecraft mc = Minecraft.getInstance();
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        double dx = h.x - px;
        double dy = h.y - py;
        double dz = h.z - pz;
        double distance = h.yIncluded ? BearingMath.distance3D(dx, dy, dz) : BearingMath.distance2D(dx, dz);
        double bearing = BearingMath.bearingDeg(px, pz, h.x, h.z);
        double yaw = BearingMath.yawDeg(px, pz, h.x, h.z);

        String dirStr = Component.translatable(BearingMath.dirKey(bearing)).getString();
        String distVal = BearingMath.fmt(distance, decimals) + " m";
        String brgVal = BearingMath.fmt(bearing, decimals) + "\u00B0  " + dirStr;
        String yawVal = BearingMath.fmt(yaw, decimals) + "\u00B0";
        String dyVal = h.yIncluded
                ? (dy >= 0 ? "+" : "") + BearingMath.fmt(dy, decimals) + " m"
                : "-";
        String posVal = h.x + ", " + h.y + ", " + h.z;

        String lDist = Component.translatable("xaerotacmap.panel.distance").getString();
        String lBrg = Component.translatable("xaerotacmap.panel.bearing").getString();
        String lYaw = Component.translatable("xaerotacmap.panel.yaw").getString();
        String lDy = Component.translatable("xaerotacmap.panel.elevation").getString();
        String lPos = Component.translatable("xaerotacmap.panel.coords").getString();

        int labelW = Math.max(Math.max(font.width(lDist), Math.max(font.width(lBrg), font.width(lYaw))),
                Math.max(font.width(lDy), font.width(lPos)));
        int valueW = Math.max(font.width(distVal),
                Math.max(font.width(brgVal), Math.max(font.width(yawVal),
                        Math.max(font.width(dyVal), font.width(posVal)))));
        int titleW = font.width(symText + name);
        int boxW = Math.max(titleW, labelW + 6 + valueW) + 14;
        int rowH = 11;
        int rows = 4 + (h.yIncluded ? 1 : 0) + (TacMapConfig.SHOW_COORDS.get() ? 1 : 0);
        int boxH = 4 + 12 + rows * rowH + 4;

        // Placement (bug #2 fix): default below-right of the cursor, but never
        // covering the player arrow's screen circle. Try the four diagonal
        // placements and take the first clean one; fall back to the default.
        int bx = 0;
        int by = 0;
        boolean placed = false;
        int[][] candidates = {
                {(int) mouseX + 16, (int) mouseY + 16},
                {(int) mouseX + 16, (int) mouseY - 16 - boxH},
                {(int) mouseX - 16 - boxW, (int) mouseY + 16},
                {(int) mouseX - 16 - boxW, (int) mouseY - 16 - boxH}
        };
        for (int[] c : candidates) {
            int cx = Math.max(2, Math.min(guiW - boxW - 2, c[0]));
            int cy = Math.max(2, Math.min(guiH - boxH - 2, c[1]));
            if (!circleHitsRect(arrowX, arrowY, PLAYER_ARROW_CLEAR_R, cx, cy, boxW, boxH)) {
                bx = cx;
                by = cy;
                placed = true;
                break;
            }
        }
        if (!placed) {
            bx = Math.max(2, Math.min(guiW - boxW - 2, (int) mouseX + 16));
            by = Math.max(2, Math.min(guiH - boxH - 2, (int) mouseY + 16));
        }

        gg.fill(bx, by, bx + boxW, by + boxH, BG);
        gg.fill(bx, by, bx + boxW, by + 1, BORDER);
        gg.fill(bx, by + boxH - 1, bx + boxW, by + boxH, BORDER);
        gg.fill(bx, by, bx + 1, by + boxH, BORDER);
        gg.fill(bx + boxW - 1, by, bx + boxW, by + boxH, BORDER);
        gg.fill(bx, by, bx + 2, by + boxH, BORDER);

        int ty = by + 5;
        int symColor = BearingMath.opaque(h.colorRgb);
        if (!symText.isEmpty()) {
            gg.drawString(font, symbol, bx + 7, ty, symColor, true);
        }
        gg.drawString(font, name, bx + 7 + font.width(symText), ty, symColor, true);
        ty += 12;

        ty = labeledRow(gg, font, bx + 7, ty, labelW + 6, lDist, distVal, TEXT);
        ty = labeledRow(gg, font, bx + 7, ty, labelW + 6, lBrg, brgVal, TEXT);
        // v4.0.7: yaw (F3 facing) row right under the compass bearing
        ty = labeledRow(gg, font, bx + 7, ty, labelW + 6, lYaw, yawVal, YAW);
        if (h.yIncluded) {
            ty = labeledRow(gg, font, bx + 7, ty, labelW + 6, lDy, dyVal, dy >= 0 ? 0xFF9BE29B : 0xFFE29B9B);
        }
        if (TacMapConfig.SHOW_COORDS.get()) {
            labeledRow(gg, font, bx + 7, ty, labelW + 6, lPos, posVal, TEXT_DIM);
        }
    }

    /** True when a circle at (cx,cy) with the given radius overlaps a rect. */
    private static boolean circleHitsRect(double cx, double cy, double r,
                                          int rx, int ry, int rw, int rh) {
        double nx = Math.max(rx, Math.min(cx, rx + rw));
        double ny = Math.max(ry, Math.min(cy, ry + rh));
        double ddx = cx - nx;
        double ddy = cy - ny;
        return ddx * ddx + ddy * ddy < r * r;
    }

    private static int labeledRow(GuiGraphics gg, Font font, int x, int y, int labelColW,
                                  String label, String value, int valueColor) {
        gg.drawString(font, label, x, y, LABEL, true);
        gg.drawString(font, value, x + labelColW, y, valueColor, true);
        return y + 11;
    }
}
