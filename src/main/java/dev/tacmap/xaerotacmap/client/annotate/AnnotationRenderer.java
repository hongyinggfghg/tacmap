package dev.tacmap.xaerotacmap.client.annotate;

import java.util.List;

import com.mojang.blaze3d.vertex.BufferBuilder;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.client.GuiMapHooks;
import dev.tacmap.xaerotacmap.client.annotate.DrawingController.Tool;
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
    private static final double ICON_HALF = 7.0D;
    private static final double HIT_R = 10.0D;

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
                drawMarker(gg, font, a, view, lastGuiW, lastGuiH, div, mx, my);
            }
            drawPreview(gg, font, view, lastGuiW, lastGuiH, div, dim);
        } finally {
            ShapeDraw.endFrame();
        }

        AnnotationToolbar.draw(gg, font, mx, my);
    }

    // ------------------------------------------------------------ marker drawing

    private static void drawMarker(GuiGraphics gg, Font font, TacAnnotation a,
                                   GuiMapHooks.ViewState view, int guiW, int guiH, int div,
                                   double mx, double my) {
        int argb = ClientMarkerStore.renderColor(a);
        boolean hover = hitTest(mx, my, view, guiW, guiH, a.dimension) == a
                && DrawingController.tool() != Tool.NONE;
        switch (a.shape) {
            case POINT: {
                double sx = view.toScreenX(a.xs[0] / div, guiW);
                double sy = view.toScreenY(a.zs[0] / div, guiH);
                if (offScreen(sx, sy, guiW, guiH, 24)) {
                    return;
                }
                AnnotationSymbolsRenderer.draw(gg, font, sx, sy, a.symbol, argb, ICON_HALF);
                if (hover) {
                    highlightRing(gg, sx, sy);
                }
                label(gg, font, sx, sy + ICON_HALF + 3, a.label, argb);
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
                label(gg, font, xs[(xs.length - 1) / 2], ys[(ys.length - 1) / 2] - 10, a.label, argb);
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
                label(gg, font, centroidX(xs), centroidY(ys), a.label, argb);
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
                    highlightRing(gg, cx, cy);
                }
                label(gg, font, cx, cy - rPx - 12, a.label, argb);
                break;
            }
            default:
                break;
        }
    }

    private static void highlightRing(GuiGraphics gg, double cx, double cy) {
        BufferBuilder bb = ShapeDraw.open();
        ShapeDraw.ring(bb, gg.pose().last(), cx, cy, ICON_HALF + 4.0D, 1.2D, ShapeDraw.abgr(HILITE), 20);
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

    private static void label(GuiGraphics gg, Font font, double x, double y, String text, int argb) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int w = font.width(text);
        int lx = (int) x - w / 2;
        int ly = (int) y;
        gg.fill(lx - 3, ly - 2, lx + w + 3, ly + 10, CHIP_BG);
        int textColor = 0xFFE8ECEE;
        gg.drawString(font, text, lx, ly, textColor, true);
        // small squad-color tick
        gg.fill(lx - 3, ly - 2, lx - 1, ly + 10, argb);
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
                drawMarker(gg, font, a, view, guiW, guiH, div, -1, -1);
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
            String rText = (int) Math.round(r * div) + " m";
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
        int x = (int) mx + 12;
        int y = (int) my + 12;
        gg.fill(x - 3, y - 2, x + w + 3, y + 10, CHIP_BG);
        gg.drawString(font, text, x, y, TEXT, true);
    }
}
