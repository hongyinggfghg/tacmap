package dev.tacmap.xaerotacmap.client.annotate;

import java.util.List;

import com.mojang.blaze3d.vertex.BufferBuilder;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.client.BearingMath;
import dev.tacmap.xaerotacmap.client.GuiMapHooks;
import dev.tacmap.xaerotacmap.client.annotate.DrawingController.Tool;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;

/**
 * Renders squad annotations on the Xaero world map (points / routes /
 * polygons / circles), plus the live drawing preview and hover highlights.
 *
 * <p>Coordinate path: raw world blocks -> divide by the dimension divisor ->
 * {@link GuiMapHooks.ViewState#toScreenX} (which folds in camera, pixel scale
 * and the window GUI scale). All overlay drawing happens in gui-screen space
 * exactly like the waypoint tactical line.</p>
 */
public final class AnnotationRenderer {

    private static final int CHIP_BG = 0xB40D1114;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int HILITE = 0xFFFFFFFF;
    /** Amber for the yaw (F3 facing) readout; dim cyan-grey for the compass one. */
    private static final int YAW = 0xFFE0B050;
    private static final int SUB_DIM = 0xFFB9C6CC;
    /** Nominal symbol half-size (gui px) at the reference zoom of 0.5 px/block. */
    private static final double ICON_HALF = 7.0D;
    private static final double REF_PX_PER_BLOCK = 0.5D;
    /** Zoom-scaling clamp: never smaller than 45% nor larger than 160% of nominal. */
    private static final double ZOOM_MIN_RATIO = 0.45D;
    private static final double ZOOM_MAX_RATIO = 1.6D;
    private static final double HIT_R = 10.0D;
    /**
     * v4.0.6 label fade band, in the same ratio units as symbolHalf
     * (pxPerBlock / REF_PX_PER_BLOCK): below LO labels are hidden entirely,
     * at or above HI they are fully opaque, in between they fade linearly.
     */
    private static final double LABEL_FADE_LO = 0.30D;
    private static final double LABEL_FADE_HI = 0.45D;

    private static boolean overlayBroken;

    // last-frame context for cross-class access (input handling, hit testing)
    private static GuiMapHooks.ViewState lastView;
    private static String lastDimension;
    private static int lastGuiW;
    private static int lastGuiH;

    private AnnotationRenderer() {
    }

    public static GuiMapHooks.ViewState lastView() {
        return lastView;
    }

    public static String lastDimension() {
        return lastDimension;
    }

    public static int lastGuiW() {
        return lastGuiW;
    }

    public static int lastGuiH() {
        return lastGuiH;
    }

    // ------------------------------------------------------------ entry

    /** Called from {@code ClientEvents} on ScreenEvent.Render.Post for map screens. */
    public static void render(ScreenEvent.Render.Post event) {
        if (overlayBroken) {
            return;
        }
        try {
            renderInner(event);
        } catch (Throwable t) {
            overlayBroken = true;
            dev.tacmap.xaerotacmap.XaeroTacMap.LOGGER.error(
                    "[TacMap] Annotation overlay crashed; disabled for this session.", t);
        }
    }

    private static void renderInner(ScreenEvent.Render.Post event) {
        if (!dev.tacmap.xaerotacmap.client.XaeroBridge.worldmapPresent()
                || !GuiMapHooks.init()) {
            return;
        }
        if (!GuiMapHooks.isMapScreen(event.getScreen())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        GuiMapHooks.ViewState view = GuiMapHooks.getViewState(event.getScreen());
        if (view == null) {
            return;
        }
        String dim = GuiMapHooks.getMapDimension(event.getScreen());
        if (dim == null) {
            dim = mc.level != null
                    ? mc.level.dimension().location().toString()
                    : "minecraft:overworld";
        }
        lastView = view;
        lastDimension = dim;
        lastGuiW = event.getScreen().width;
        lastGuiH = event.getScreen().height;

        int div = DrawingController.dimDivOf(dim);
        DrawingController.updateCursor(view, lastGuiW, lastGuiH,
                event.getMouseX(), event.getMouseY(), div);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        double mx = event.getMouseX();
        double my = event.getMouseY();

        ShapeDraw.beginFrame();
        try {
            List<TacAnnotation> markers = ClientMarkerStore.markersIn(dim);
            for (TacAnnotation a : markers) {
                drawMarker(gg, font, a, view, lastGuiW, lastGuiH, div, mx, my, false);
            }
            drawPreview(gg, font, view, lastGuiW, lastGuiH, div, dim);
        } finally {
            ShapeDraw.endFrame();
        }

        AnnotationToolbar.draw(gg, font, mx, my);

        // active-tool usage hint next to the cursor (v4.0.5: actually wired up)
        drawToolHint(gg, font, mx, my);
    }

    // ------------------------------------------------------------ marker drawing

    /**
     * Zoom-responsive symbol half-size: grows/shrinks with the map zoom so
     * markers do not dominate the map when zoomed out. Clamped to stay legible.
     */
    private static double symbolHalf(GuiMapHooks.ViewState view) {
        double pxPerBlock = view.scale / view.guiScale;
        double ratio = pxPerBlock / REF_PX_PER_BLOCK;
        ratio = Math.max(ZOOM_MIN_RATIO, Math.min(ZOOM_MAX_RATIO, ratio));
        return ICON_HALF * ratio;
    }

    private static void drawMarker(GuiGraphics gg, Font font, TacAnnotation a,
                                   GuiMapHooks.ViewState view, int guiW, int guiH, int div,
                                   double mx, double my, boolean forceLabel) {
        int argb = ClientMarkerStore.renderColor(a);
        boolean hover = hitTest(mx, my, view, guiW, guiH, a.dimension) == a
                && DrawingController.tool() != Tool.NONE;
        double iconHalf = symbolHalf(view);
        // v4.0.6: name chips fade out when zoomed out (clutter), but the
        // marker under the cursor and the shape being named stay readable
        double labelA = (forceLabel || hover) ? 1.0D : labelAlpha(view);
        // v4.0.7: dual degree readout (yaw + compass + distance) under the name
        // v4.0.9: behind config map.chipReadout, default OFF = name-only chips
        String[] sub = TacMapConfig.MAP_CHIP_READOUT.get() ? bearingSubline(a) : null;
        switch (a.shape) {
            case POINT: {
                double sx = view.toScreenX(a.xs[0] / div, guiW);
                double sy = view.toScreenY(a.zs[0] / div, guiH);
                if (offScreen(sx, sy, guiW, guiH, 24)) {
                    return;
                }
                AnnotationSymbolsRenderer.draw(gg, font, sx, sy, a.symbol, argb, iconHalf);
                if (hover) {
                    highlightRing(gg, sx, sy, iconHalf);
                }
                label(gg, font, sx, sy + iconHalf + 3, a.label, sub, argb, labelA);
                break;
            }
            case ROUTE: {
                double[] xs = toScreenX(a.xs, div, view, guiW);
                double[] ys = toScreenY(a.zs, div, view, guiH);
                int abgr = ShapeDraw.abgr(argb);
                BufferBuilder bb = ShapeDraw.open();
                ShapeDraw.strokeDashed(bb, gg.pose().last(), xs, ys, 1.0D, false, 7.0D, 5.0D, abgr);
                arrowAtEnd(bb, gg.pose().last(), xs, ys, argb);
                ShapeDraw.flush(bb);
                if (hover) {
                    highlightPolyline(gg, xs, ys, false);
                }
                label(gg, font, xs[(xs.length - 1) / 2], ys[(ys.length - 1) / 2] - 10, a.label, sub, argb, labelA);
                break;
            }
            case POLYGON: {
                double[] xs = toScreenX(a.xs, div, view, guiW);
                double[] ys = toScreenY(a.zs, div, view, guiH);
                int abgr = ShapeDraw.abgr(argb);
                int fillAbgr = ShapeDraw.abgr((argb & 0x00FFFFFF) | 0x40000000);
                BufferBuilder bb = ShapeDraw.open();
                ShapeDraw.fillPoly(bb, gg.pose().last(), xs, ys, fillAbgr);
                ShapeDraw.strokePolyline(bb, gg.pose().last(), xs, ys, 1.0D, true, abgr);
                ShapeDraw.flush(bb);
                if (hover) {
                    highlightPolyline(gg, xs, ys, true);
                }
                label(gg, font, centroidX(xs), centroidY(ys), a.label, sub, argb, labelA);
                break;
            }
            case CIRCLE: {
                double cx = view.toScreenX(a.xs[0] / div, guiW);
                double cy = view.toScreenY(a.zs[0] / div, guiH);
                double rPx = a.radius / div * view.scale / view.guiScale;
                if (offScreen(cx, cy, guiW, guiH, rPx + 24)) {
                    return;
                }
                int abgr = ShapeDraw.abgr(argb);
                int fillAbgr = ShapeDraw.abgr((argb & 0x00FFFFFF) | 0x40000000);
                BufferBuilder bb = ShapeDraw.open();
                ShapeDraw.fillCircle(bb, gg.pose().last(), cx, cy, rPx, fillAbgr, 48);
                ShapeDraw.ring(bb, gg.pose().last(), cx, cy, rPx, 1.0D, abgr, 48);
                ShapeDraw.fillCircle(bb, gg.pose().last(), cx, cy, 1.6D, abgr, 8);
                ShapeDraw.flush(bb);
                if (hover) {
                    highlightRing(gg, cx, cy, Math.max(4.0D, Math.min(rPx, 12.0D)));
                }
                label(gg, font, cx, cy - rPx - 12, a.label, sub, argb, labelA);
                break;
            }
            default:
                break;
        }
    }

    private static void highlightRing(GuiGraphics gg, double cx, double cy, double half) {
        BufferBuilder bb = ShapeDraw.open();
        ShapeDraw.ring(bb, gg.pose().last(), cx, cy, half + 4.0D, 1.2D, ShapeDraw.abgr(HILITE), 20);
        ShapeDraw.flush(bb);
    }

    private static void highlightPolyline(GuiGraphics gg, double[] xs, double[] ys, boolean closed) {
        BufferBuilder bb = ShapeDraw.open();
        ShapeDraw.strokePolyline(bb, gg.pose().last(), xs, ys, 2.2D, closed, ShapeDraw.abgr(HILITE));
        ShapeDraw.flush(bb);
    }

    private static void arrowAtEnd(BufferBuilder bb, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                   double[] xs, double[] ys, int argb) {
        int n = xs.length;
        if (n < 2) {
            return;
        }
        double dx = xs[n - 1] - xs[n - 2];
        double dy = ys[n - 1] - ys[n - 2];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4D) {
            return;
        }
        ShapeDraw.arrow(bb, pose, xs[n - 1], ys[n - 1], dx / len, dy / len, 9.0D, 5.0D,
                ShapeDraw.abgr(argb));
    }

    /**
     * Zoom-driven label opacity (v4.0.6). The chip background, text and
     * squad-color tick all share one alpha so the chip dissolves cleanly
     * instead of popping. Hovered markers and the naming preview pass 1.0.
     */
    private static void label(GuiGraphics gg, Font font, double x, double y,
                              String text, String[] sub, int argb, double alpha) {
        boolean hasName = text != null && !text.isEmpty();
        boolean hasSub = sub != null && sub.length >= 2 && !sub[0].isEmpty();
        if ((!hasName && !hasSub) || alpha < 0.04D) {
            return;
        }
        int wName = hasName ? font.width(text) : 0;
        int wSub = hasSub ? font.width(sub[0]) + 5 + font.width(sub[1]) : 0;
        int w = Math.max(wName, wSub);
        int bottom = hasSub ? 20 : 10;
        int lx = (int) x - w / 2;
        int ly = (int) y;
        int chipA = (int) (0xB4 * alpha);
        gg.fill(lx - 3, ly - 2, lx + w + 3, ly + bottom,
                (chipA << 24) | (CHIP_BG & 0x00FFFFFF));
        // vanilla Font snaps alpha values under ~4/255 to fully opaque, so
        // keep a translucent floor instead of letting the text pop black
        int textA = Math.max(8, (int) (255.0D * alpha));
        if (hasName) {
            gg.drawString(font, text, lx, ly, (textA << 24) | (TEXT & 0x00FFFFFF), true);
        }
        if (hasSub) {
            // v4.0.7: row 2 = yaw readout (amber, the fire-calling number)
            // followed by the compass readout and the distance
            int sy = ly + 10;
            gg.drawString(font, sub[0], lx, sy, (textA << 24) | (YAW & 0x00FFFFFF), true);
            gg.drawString(font, sub[1], lx + font.width(sub[0]) + 5, sy,
                    (textA << 24) | (SUB_DIM & 0x00FFFFFF), true);
        }
        // small squad-color tick
        int tickA = Math.max(8, (int) (((argb >>> 24) & 0xFF) * alpha));
        gg.fill(lx - 3, ly - 2, lx - 1, ly + bottom,
                (tickA << 24) | (argb & 0x00FFFFFF));
    }

    /**
     * v4.0.7 dual degree readout for an annotation chip: [yaw segment,
     * compass+distance segment], or null when there is nothing to show.
     *
     * <p>Yaw follows the squad fire-calling spec (south = 0, west = +90,
     * north = 180, east = -90 - the F3 facing number); the compass readout
     * keeps the existing north = 0 convention. Both are measured from the
     * LIVE player position, so the row follows the player in real time.
     * Hidden when the player is in another dimension than the annotation.</p>
     */
    private static String[] bearingSubline(TacAnnotation a) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        String pdim = mc.level.dimension().location().toString();
        if (!pdim.equals(a.dimension)) {
            return null;
        }
        double wx;
        double wz;
        switch (a.shape) {
            case ROUTE: {
                int idx = (a.xs.length - 1) / 2;
                wx = a.xs[idx];
                wz = a.zs[idx];
                break;
            }
            case POLYGON: {
                double sx = 0;
                double sz = 0;
                for (int i = 0; i < a.xs.length; i++) {
                    sx += a.xs[i];
                    sz += a.zs[i];
                }
                wx = sx / a.xs.length;
                wz = sz / a.zs.length;
                break;
            }
            default: {
                wx = a.xs[0];
                wz = a.zs[0];
                break;
            }
        }
        double pxx = mc.player.getX();
        double pzz = mc.player.getZ();
        double dist = Math.sqrt(sq(wx - pxx) + sq(wz - pzz));
        if (dist < 0.5D) {
            return null;
        }
        int yawI = BearingMath.yawRound(BearingMath.yawDeg(pxx, pzz, wx, wz));
        int cmpI = ((int) Math.round(BearingMath.bearingDeg(pxx, pzz, wx, wz))) % 360;
        String yawTag = Component.translatable("xaerotacmap.brg.yaw_s").getString();
        String cmpTag = Component.translatable("xaerotacmap.brg.cmp_s").getString();
        String distStr = Component.translatable("xaerotacmap.brg.dist_fmt",
                (int) Math.round(dist)).getString();
        String segYaw = yawTag + yawI + "\u00B0";
        String segRest = cmpTag + cmpI + "\u00B0 " + distStr;
        return new String[]{segYaw, segRest};
    }

    /**
     * Label opacity for the current zoom: 1 at/above {@link #LABEL_FADE_HI},
     * 0 at/below {@link #LABEL_FADE_LO}, linear in between. Uses the same
     * pxPerBlock normalization as {@link #symbolHalf}.
     */
    private static double labelAlpha(GuiMapHooks.ViewState view) {
        double pxPerBlock = view.scale / view.guiScale;
        double ratio = pxPerBlock / REF_PX_PER_BLOCK;
        if (ratio >= LABEL_FADE_HI) {
            return 1.0D;
        }
        if (ratio <= LABEL_FADE_LO) {
            return 0.0D;
        }
        return (ratio - LABEL_FADE_LO) / (LABEL_FADE_HI - LABEL_FADE_LO);
    }

    private static double centroidX(double[] xs) {
        double s = 0;
        for (double v : xs) {
            s += v;
        }
        return s / xs.length;
    }

    private static double centroidY(double[] ys) {
        double s = 0;
        for (double v : ys) {
            s += v;
        }
        return s / ys.length;
    }

    private static double[] toScreenX(double[] raw, int div, GuiMapHooks.ViewState view, int guiW) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = view.toScreenX(raw[i] / div, guiW);
        }
        return out;
    }

    private static double[] toScreenY(double[] raw, int div, GuiMapHooks.ViewState view, int guiH) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = view.toScreenY(raw[i] / div, guiH);
        }
        return out;
    }

    private static boolean offScreen(double x, double y, int w, int h, double margin) {
        return x < -margin || x > w + margin || y < -margin || y > h + margin;
    }

    // ------------------------------------------------------------ preview

    private static void drawPreview(GuiGraphics gg, Font font, GuiMapHooks.ViewState view,
                                    int guiW, int guiH, int div, String dim) {
        List<double[]> pts = DrawingController.pendingVertices();
        int argb = ClientMarkerStore.squadColor();
        int abgr = ShapeDraw.abgr(argb);

        if (DrawingController.isAwaitingLabel() && DrawingController.pendingAnnotation() != null) {
            TacAnnotation a = DrawingController.pendingAnnotation();
            if (a.dimension.equals(dim)) {
                // forceLabel: the shape being named always shows its label
                drawMarker(gg, font, a, view, guiW, guiH, div, -1, -1, true);
            }
            return;
        }

        Tool tool = DrawingController.tool();
        if (tool == Tool.NONE || pts.isEmpty()) {
            return;
        }

        if (tool == Tool.CIRCLE && pts.size() == 1 && DrawingController.cursorValid()) {
            double cx = view.toScreenX(pts.get(0)[0], guiW);
            double cy = view.toScreenY(pts.get(0)[1], guiH);
            double r = Math.sqrt(sq(pts.get(0)[0] - DrawingController.cursorMapX())
                    + sq(pts.get(0)[1] - DrawingController.cursorMapZ()));
            double rPx = r / div * view.scale / view.guiScale;
            BufferBuilder bb = ShapeDraw.open();
            ShapeDraw.ring(bb, gg.pose().last(), cx, cy, rPx, 1.2D, abgr, 48);
            ShapeDraw.seg(bb, gg.pose().last(), cx, cy, cx + rPx, cy, 1.0D, abgr);
            ShapeDraw.flush(bb);
            String rText = Component.translatable("xaerotacmap.annotate.radius_fmt",
                    (int) Math.round(r * div)).getString();
            gg.fill((int) cx + 4, (int) cy - 12, (int) cx + 10 + font.width(rText), (int) cy - 2,
                    CHIP_BG);
            gg.drawString(font, rText, (int) cx + 7, (int) cy - 11, TEXT, true);
            return;
        }

        double[] xs = new double[pts.size()];
        double[] ys = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = view.toScreenX(pts.get(i)[0], guiW);
            ys[i] = view.toScreenY(pts.get(i)[1], guiH);
        }
        BufferBuilder bb = ShapeDraw.open();
        boolean closed = tool == Tool.POLYGON && pts.size() >= 3;
        ShapeDraw.strokeDashed(bb, gg.pose().last(), xs, ys, 1.1D, closed, 6.0D, 4.0D, abgr);
        if (tool == Tool.ROUTE && DrawingController.cursorValid() && !closed) {
            double cx = view.toScreenX(DrawingController.cursorMapX(), guiW);
            double cy = view.toScreenY(DrawingController.cursorMapZ(), guiH);
            ShapeDraw.seg(bb, gg.pose().last(), xs[xs.length - 1], ys[ys.length - 1], cx, cy,
                    1.1D, abgr);
        }
        if (tool == Tool.POLYGON && DrawingController.cursorValid() && !closed) {
            double cx = view.toScreenX(DrawingController.cursorMapX(), guiW);
            double cy = view.toScreenY(DrawingController.cursorMapZ(), guiH);
            ShapeDraw.seg(bb, gg.pose().last(), cx, cy, xs[0], ys[0], 1.0D,
                    ShapeDraw.abgr((argb & 0x00FFFFFF) | 0x80000000));
        }
        ShapeDraw.flush(bb);

        // v4.0.6: translucent area preview - the region "fills in" while you
        // click, so selecting a quadrilateral reads as an area, not a zigzag
        if (closed) {
            int fillAbgr = ShapeDraw.abgr((argb & 0x00FFFFFF) | 0x30000000);
            BufferBuilder fb = ShapeDraw.open();
            ShapeDraw.fillPoly(fb, gg.pose().last(), xs, ys, fillAbgr);
            ShapeDraw.flush(fb);
            // close affordance: glow around the first vertex when the cursor
            // is inside the click-to-close window
            if (DrawingController.cursorValid()) {
                double cx = view.toScreenX(DrawingController.cursorMapX(), guiW);
                double cy = view.toScreenY(DrawingController.cursorMapZ(), guiH);
                double dx0 = cx - xs[0];
                double dy0 = cy - ys[0];
                if (dx0 * dx0 + dy0 * dy0 <= 256.0D) {
                    BufferBuilder hb = ShapeDraw.open();
                    ShapeDraw.ring(hb, gg.pose().last(), xs[0], ys[0], 5.5D, 1.2D,
                            ShapeDraw.abgr(HILITE), 16);
                    ShapeDraw.flush(hb);
                }
            }
        }

        // vertex handles
        bb = ShapeDraw.open();
        for (int i = 0; i < xs.length; i++) {
            ShapeDraw.fillCircle(bb, gg.pose().last(), xs[i], ys[i], 2.2D, abgr, 10);
        }
        ShapeDraw.flush(bb);
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------ hit testing

    /**
     * Nearest annotation under the cursor (screen-space threshold), or null.
     * Only annotations of the given dimension are considered.
     */
    public static TacAnnotation hitTest(double mx, double my, GuiMapHooks.ViewState view,
                                        int guiW, int guiH, String dimension) {
        if (view == null || dimension == null) {
            return null;
        }
        TacAnnotation best = null;
        double bestDist = HIT_R;
        for (TacAnnotation a : ClientMarkerStore.markersIn(dimension)) {
            double d = distTo(a, mx, my, view, guiW, guiH);
            if (d >= 0.0D && d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    private static double distTo(TacAnnotation a, double mx, double my,
                                 GuiMapHooks.ViewState view, int guiW, int guiH) {
        int div = DrawingController.dimDivOf(a.dimension);
        switch (a.shape) {
            case POINT: {
                double sx = view.toScreenX(a.xs[0] / div, guiW);
                double sy = view.toScreenY(a.zs[0] / div, guiH);
                return Math.sqrt(sq(mx - sx) + sq(my - sy));
            }
            case ROUTE: {
                double[] xs = toScreenX(a.xs, div, view, guiW);
                double[] ys = toScreenY(a.zs, div, view, guiH);
                double best = Double.MAX_VALUE;
                for (int i = 0; i + 1 < xs.length; i++) {
                    best = Math.min(best, segDist(mx, my, xs[i], ys[i], xs[i + 1], ys[i + 1]));
                }
                return best;
            }
            case POLYGON: {
                double[] xs = toScreenX(a.xs, div, view, guiW);
                double[] ys = toScreenY(a.zs, div, view, guiH);
                double best = Double.MAX_VALUE;
                for (int i = 0; i < xs.length; i++) {
                    int j = (i + 1) % xs.length;
                    best = Math.min(best, segDist(mx, my, xs[i], ys[i], xs[j], ys[j]));
                }
                if (best > HIT_R && insidePolygon(xs, ys, mx, my)) {
                    return 0.0D;
                }
                return best;
            }
            case CIRCLE: {
                double cx = view.toScreenX(a.xs[0] / div, guiW);
                double cy = view.toScreenY(a.zs[0] / div, guiH);
                double rPx = a.radius / div * view.scale / view.guiScale;
                double d = Math.sqrt(sq(mx - cx) + sq(my - cy));
                return Math.abs(d - rPx) <= HIT_R ? Math.abs(d - rPx)
                        : (d < rPx ? 0.0D : Double.MAX_VALUE);
            }
            default:
                return Double.MAX_VALUE;
        }
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1.0E-9D) {
            return Math.sqrt(sq(px - ax) + sq(py - ay));
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double cx = ax + dx * t;
        double cy = ay + dy * t;
        return Math.sqrt(sq(px - cx) + sq(py - cy));
    }

    private static boolean insidePolygon(double[] xs, double[] ys, double px, double py) {
        boolean inside = false;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            boolean intersect = (ys[i] > py) != (ys[j] > py)
                    && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i] + 1.0E-12D) + xs[i];
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Tooltip-style hint of the active tool, drawn next to the cursor. */
    public static void drawToolHint(GuiGraphics gg, Font font, double mx, double my) {
        Tool t = DrawingController.tool();
        if (t == Tool.NONE || DrawingController.isAwaitingLabel()) {
            return;
        }
        String key = switch (t) {
            case POINT -> "xaerotacmap.annotate.hint_point";
            case ROUTE -> "xaerotacmap.annotate.hint_route";
            case POLYGON -> "xaerotacmap.annotate.hint_polygon";
            case CIRCLE -> "xaerotacmap.annotate.hint_circle";
            case ERASE -> "xaerotacmap.annotate.hint_erase";
            default -> null;
        };
        if (key == null) {
            return;
        }
        String text = Component.translatable(key).getString();
        int w = font.width(text);
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x = (int) mx + 12;
        int y = (int) my + 12;
        if (x + w + 6 > sw) {
            x = (int) mx - w - 15;
        }
        if (y + 12 > sh) {
            y = (int) my - 14;
        }
        gg.fill(x - 3, y - 2, x + w + 3, y + 10, CHIP_BG);
        gg.drawString(font, text, x, y, TEXT, true);
    }
}
