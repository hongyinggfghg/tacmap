package dev.tacmap.xaerotacmap.client.annotate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.client.GuiMapHooks;
import dev.tacmap.xaerotacmap.net.TacNet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side annotation drawing state machine.
 *
 * <p>Flow: pick a tool on the map toolbar (left edge) - click to place
 * vertices (route/polygon/circle need several), finish with right click or
 * Enter, then type an optional label inline (Enter = confirm, Esc = cancel).
 * Committed shapes are sent to the server which assigns them to the player's
 * squad and echoes them to every squad member.</p>
 *
 * <p>Vertices are stored in MAP-SPACE coordinates (world blocks divided by
 * the dimension divisor) captured at click time, so camera movement between
 * clicks never skews a shape.</p>
 */
public final class DrawingController {

    /** Left-edge toolbar tools. */
    public enum Tool {
        NONE, POINT, ROUTE, POLYGON, CIRCLE, ERASE
    }

    private static Tool tool = Tool.NONE;
    /** Defaults to a FRIENDLY symbol - enemy ones must be picked explicitly. */
    private static TacAnnotation.Symbol pointSymbol = TacAnnotation.Symbol.RALLY;
    private static boolean paletteOpen;

    /** In-progress vertices, map-space. */
    private static final List<double[]> pending = new ArrayList<>();
    /** Live cursor position in map-space (refreshed by the renderer). */
    private static double cursorMapX;
    private static double cursorMapZ;
    private static boolean cursorValid;

    /** Label capture state after a shape commit. */
    private static boolean awaitingLabel;
    private static final StringBuilder labelBuf = new StringBuilder();
    private static TacAnnotation pendingAnno;
    private static int[] labelAnchor = new int[2];

    /** Sequence numbers for default labels. */
    private static int seqPoint;
    private static int seqRoute;
    private static int seqArea;

    private DrawingController() {
    }

    // ------------------------------------------------------------ mode control

    public static Tool tool() {
        return tool;
    }

    public static void setTool(Tool t) {
        tool = t;
        pending.clear();
        cursorValid = false;
        awaitingLabel = false;
        pendingAnno = null;
        if (t != Tool.POINT) {
            // symbol only matters for points; keep palette available anyway
        }
    }

    public static TacAnnotation.Symbol pointSymbol() {
        return pointSymbol;
    }

    public static void setPointSymbol(TacAnnotation.Symbol s) {
        pointSymbol = s;
        paletteOpen = false;
    }

    public static boolean paletteOpen() {
        return paletteOpen;
    }

    public static void togglePalette() {
        paletteOpen = !paletteOpen;
    }

    public static void closePalette() {
        paletteOpen = false;
    }

    /** Full reset (screen change, logout, finishing/cancel). */
    public static void reset() {
        tool = Tool.NONE;
        pending.clear();
        cursorValid = false;
        awaitingLabel = false;
        pendingAnno = null;
        paletteOpen = false;
    }

    // ------------------------------------------------------------ geometry io

    /** Divisor applied by Xaero to a dimension's coordinates (nether 8, else 1). */
    public static int dimDivOf(String dimension) {
        return dimension != null && dimension.equals("minecraft:the_nether") ? 8 : 1;
    }

    /** Current cursor in map-space (valid after the renderer refreshes it). */
    public static double cursorMapX() {
        return cursorMapX;
    }

    public static double cursorMapZ() {
        return cursorMapZ;
    }

    public static boolean cursorValid() {
        return cursorValid;
    }

    public static List<double[]> pendingVertices() {
        return pending;
    }

    public static boolean isAwaitingLabel() {
        return awaitingLabel;
    }

    public static TacAnnotation pendingAnnotation() {
        return pendingAnno;
    }

    public static int[] labelAnchor() {
        return labelAnchor;
    }

    public static String labelBuffer() {
        return labelBuf.toString();
    }

    /** Refreshes the cached cursor position (map-space) from the renderer. */
    public static void updateCursor(GuiMapHooks.ViewState view, int guiW, int guiH,
                                    double mouseX, double mouseY, int dimDiv) {
        cursorMapX = view.worldFromScreenX(mouseX, guiW);
        cursorMapZ = view.worldFromScreenY(mouseY, guiH);
        cursorValid = mouseX >= 0 && mouseX <= guiW && mouseY >= 0 && mouseY <= guiH;
        cursorDiv = dimDiv;
    }

    private static int cursorDiv = 1;

    // ------------------------------------------------------------ input

    /**
     * Handles a click on the map area (toolbar clicks are consumed before this).
     *
     * @return true when the click was consumed (caller must cancel the event)
     */
    public static boolean onMouseClick(double mouseX, double mouseY, int button,
                                       GuiMapHooks.ViewState view, int guiW, int guiH,
                                       String dimension) {
        if (tool == Tool.NONE || awaitingLabel) {
            return awaitingLabel;
        }
        if (!ClientMarkerStore.inSquad()) {
            return false;
        }
        double mapX = view.worldFromScreenX(mouseX, guiW);
        double mapZ = view.worldFromScreenY(mouseY, guiH);

        switch (tool) {
            case POINT: {
                if (button == 0) {
                    pending.clear();
                    pending.add(new double[]{mapX, mapZ});
                    beginLabel(TacAnnotation.ShapeType.POINT, dimension, view, guiW, guiH, mouseX, mouseY);
                    return true;
                }
                return false;
            }
            case ROUTE: {
                if (button == 0) {
                    if (pending.size() < 64) {
                        pending.add(new double[]{mapX, mapZ});
                    }
                    return true;
                }
                if (button == 1 && pending.size() >= 2) {
                    beginLabel(TacAnnotation.ShapeType.ROUTE, dimension, view, guiW, guiH, mouseX, mouseY);
                    return true;
                }
                return button == 1;
            }
            case POLYGON: {
                if (button == 0) {
                    // clicking near the first vertex closes the ring
                    if (pending.size() >= 3 && nearFirst(mouseX, mouseY, view, guiW, guiH)) {
                        beginLabel(TacAnnotation.ShapeType.POLYGON, dimension, view, guiW, guiH, mouseX, mouseY);
                        return true;
                    }
                    // v4.0.6: naive append first; if the new vertex would make
                    // the ring cross itself (e.g. clicking the 4th corner of a
                    // quad in "Z" order), try inserting it at another position
                    // that keeps the ring simple - only reject when nothing works
                    int placed = placePolygonVertex(mapX, mapZ);
                    if (placed < 0) {
                        warnSelfIntersect();
                    } else if (placed > 0) {
                        hintVertexAdjusted();
                    }
                    return true;
                }
                if (button == 1 && pending.size() >= 3) {
                    beginLabel(TacAnnotation.ShapeType.POLYGON, dimension, view, guiW, guiH, mouseX, mouseY);
                    return true;
                }
                return button == 1;
            }
            case CIRCLE: {
                if (button == 0) {
                    if (pending.isEmpty()) {
                        pending.add(new double[]{mapX, mapZ});
                        return true;
                    }
                    double r = Math.sqrt(square(pending.get(0)[0] - mapX) + square(pending.get(0)[1] - mapZ));
                    if (r < 4.0D) {
                        return true; // degenerate radius, ignore
                    }
                    pending.add(new double[]{r, 0});
                    beginLabel(TacAnnotation.ShapeType.CIRCLE, dimension, view, guiW, guiH, mouseX, mouseY);
                    return true;
                }
                return false;
            }
            case ERASE: {
                if (button == 0) {
                    TacAnnotation hit = AnnotationRenderer.hitTest(mouseX, mouseY, view, guiW, guiH, dimension);
                    if (hit != null && ClientMarkerStore.mayDelete(hit)) {
                        TacNet.CHANNEL.sendToServer(new TacNet.DeleteMarkerPkt(hit.id));
                    }
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    private static double square(double v) {
        return v * v;
    }

    /**
     * Would inserting (mapX, mapZ) as the LAST polygon vertex produce a
     * self-intersecting ring? Tests the new edge (last -&gt; new) and the
     * implicit closing edge (new -&gt; first) against every non-adjacent edge
     * already in the pending list. Adjacent edges sharing an endpoint are
     * skipped (touching at a shared vertex is legal).
     */
    private static boolean wouldSelfIntersect(double mapX, double mapZ) {
        int n = pending.size();
        if (n < 2) {
            return false;
        }
        double[] first = pending.get(0);
        double[] last = pending.get(n - 1);
        for (int i = 0; i + 1 < n; i++) {
            double[] a = pending.get(i);
            double[] b = pending.get(i + 1);
            boolean sharedWithNewEdge = (i == n - 2);   // touches 'last'
            boolean sharedWithCloseEdge = (i == 0);     // touches 'first'
            if (!sharedWithNewEdge && ShapeDraw.segmentsIntersect(
                    last[0], last[1], mapX, mapZ, a[0], a[1], b[0], b[1])) {
                return true;
            }
            if (!sharedWithCloseEdge && ShapeDraw.segmentsIntersect(
                    mapX, mapZ, first[0], first[1], a[0], a[1], b[0], b[1])) {
                return true;
            }
        }
        return false;
    }

    /** Vertex cap shared by append and insert paths. */
    private static final int MAX_POLY_VERTS = 128;

    /**
     * Places the next polygon vertex, self-healing the ring when possible
     * (v4.0.6).
     *
     * @return 0 = appended at the end (natural order),
     *         1 = auto-inserted at an earlier position to keep the ring
     *         simple (the "draw the 4 corners in any order" case),
     *        -1 = every position would self-intersect, click rejected
     */
    private static int placePolygonVertex(double mapX, double mapZ) {
        int n = pending.size();
        if (n >= MAX_POLY_VERTS) {
            return -1;
        }
        if (n < 3 || !wouldSelfIntersect(mapX, mapZ)) {
            pending.add(new double[]{mapX, mapZ});
            return 0;
        }
        // try inserting before each existing vertex; the first position that
        // yields a simple closed ring wins (usually the quad corner case)
        for (int k = 0; k < n; k++) {
            if (ringSimpleWith(mapX, mapZ, k)) {
                pending.add(k, new double[]{mapX, mapZ});
                return 1;
            }
        }
        return -1;
    }

    /**
     * Would the pending ring stay simple if the new point were inserted
     * BEFORE index k? Builds the candidate ring and runs a full O(n^2)
     * simplicity check (n &lt;= 128, click-time only - negligible).
     */
    static boolean ringSimpleWith(double mapX, double mapZ, int k) {
        int n = pending.size();
        double[][] ring = new double[n + 1][];
        for (int i = 0, j = 0; i <= n; i++, j++) {
            if (i == k) {
                ring[i] = new double[]{mapX, mapZ};
                j--;
            } else {
                ring[i] = pending.get(j);
            }
        }
        return isSimpleRing(ring);
    }

    /**
     * Full closed-ring simplicity test: no two non-adjacent edges may
     * cross. Adjacent edges (sharing a vertex, wrap-around aware) are
     * skipped - touching at a shared vertex is legal.
     */
    static boolean isSimpleRing(double[][] ring) {
        int n = ring.length;
        if (n < 3) {
            return true;
        }
        for (int i = 0; i < n; i++) {
            double[] a = ring[i];
            double[] b = ring[(i + 1) % n];
            // j starts at i+2: edge (i) and edge (i+1) are adjacent (share a
            // vertex - touching there is legal and MUST NOT count as a cross)
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) {
                    continue; // first and last edges are adjacent via the closure
                }
                double[] c = ring[j];
                double[] d = ring[(j + 1) % n];
                if (ShapeDraw.segmentsIntersect(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Yellow action-bar warning when a polygon vertex is rejected. */
    private static void warnSelfIntersect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("xaerotacmap.annotate.poly_cross"), true);
        }
    }

    /** Action-bar note when a vertex was auto-inserted elsewhere (v4.0.6). */
    private static void hintVertexAdjusted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("xaerotacmap.annotate.poly_adjust"), true);
        }
    }

    private static boolean nearFirst(double mouseX, double mouseY,
                                     GuiMapHooks.ViewState view, int guiW, int guiH) {
        if (pending.isEmpty()) {
            return false;
        }
        double[] first = pending.get(0);
        double sx = view.toScreenX(first[0], guiW);
        double sy = view.toScreenY(first[1], guiH);
        double dx = mouseX - sx;
        double dy = mouseY - sy;
        return dx * dx + dy * dy <= 256.0D; // 16 gui px (v4.0.6: wider close window)
    }

    /** Builds the annotation from collected vertices and switches to label capture. */
    private static void beginLabel(TacAnnotation.ShapeType shape, String dimension,
                                   GuiMapHooks.ViewState view, int guiW, int guiH,
                                   double mouseX, double mouseY) {
        int div = DrawingController.dimDivOf(dimension);
        TacAnnotation.Symbol symbol = shape == TacAnnotation.ShapeType.POINT
                ? pointSymbol
                : TacAnnotation.Symbol.FLAG;
        double[] mapVerts = currentVerts(shape);
        double radius = 0.0D;
        double[] xs;
        double[] zs;
        if (shape == TacAnnotation.ShapeType.CIRCLE) {
            // mapVerts = [cx, cz, radius] (flat, 3 entries)
            xs = new double[1];
            zs = new double[1];
            xs[0] = mapVerts[0] * div;
            zs[0] = mapVerts[1] * div;
            radius = mapVerts[2];
        } else {
            // mapVerts is FLAT pairs [x0,z0,x1,z1,...]; xs/zs are PARALLEL per-vertex
            int n = mapVerts.length / 2;
            xs = new double[n];
            zs = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = mapVerts[i * 2] * div;
                zs[i] = mapVerts[i * 2 + 1] * div;
            }
        }
        String defaultLabel = defaultLabel(shape, symbol);
        pendingAnno = new TacAnnotation(UUID.randomUUID(), shape, symbol, dimension,
                xs, zs, radius, defaultLabel,
                ClientMarkerStore.selfId(), playerName());
        labelBuf.setLength(0);
        awaitingLabel = true;
        labelAnchor[0] = (int) mouseX;
        labelAnchor[1] = (int) mouseY;
        pending.clear();
    }

    /** Flattens pending vertices; for CIRCLE returns [cx, cz, radius]. */
    private static double[] currentVerts(TacAnnotation.ShapeType shape) {
        if (shape == TacAnnotation.ShapeType.CIRCLE) {
            double[] c = pending.get(0);
            double r = cursorValid
                    ? Math.sqrt(square(c[0] - cursorMapX) + square(c[1] - cursorMapZ))
                    : 16.0D;
            return new double[]{c[0], c[1], Math.max(r, 4.0D)};
        }
        double[] out = new double[pending.size() * 2];
        for (int i = 0; i < pending.size(); i++) {
            out[i * 2] = pending.get(i)[0];
            out[i * 2 + 1] = pending.get(i)[1];
        }
        return out;
    }

    private static String playerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() != null ? mc.getUser().getName() : "?";
    }

    private static String defaultLabel(TacAnnotation.ShapeType shape, TacAnnotation.Symbol symbol) {
        switch (shape) {
            case POINT: {
                seqPoint++;
                String base = Component.translatable(symbol.langKey()).getString();
                return base + "-" + seqPoint;
            }
            case ROUTE: {
                seqRoute++;
                return Component.translatable("xaerotacmap.annotate.default_route").getString() + "-" + seqRoute;
            }
            default: {
                seqArea++;
                return Component.translatable("xaerotacmap.annotate.default_area").getString() + "-" + seqArea;
            }
        }
    }

    /**
     * Screen-space keyboard handling (map screen open).
     *
     * @return true when consumed (caller must cancel the event)
     */
    public static boolean onKeyPressed(int keyCode, int scanCode) {
        if (awaitingLabel) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmLabel();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelLabel();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && labelBuf.length() > 0) {
                labelBuf.deleteCharAt(labelBuf.length() - 1);
                return true;
            }
            return true;
        }
        if (tool == Tool.NONE) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            reset();
            return true;
        }
        // v4.0.6: Backspace/Delete removes the last placed vertex (route or
        // polygon) so a misclick no longer forces a full Esc restart
        if ((keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE)
                && !pending.isEmpty()) {
            pending.remove(pending.size() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (tool == Tool.ROUTE && pending.size() >= 2) {
                String dim = AnnotationRenderer.lastDimension();
                GuiMapHooks.ViewState view = AnnotationRenderer.lastView();
                if (dim != null && view != null) {
                    beginLabel(TacAnnotation.ShapeType.ROUTE, dim, view,
                            AnnotationRenderer.lastGuiW(), AnnotationRenderer.lastGuiH(),
                            cursorScreenX(), cursorScreenY());
                    return true;
                }
            }
            if (tool == Tool.POLYGON && pending.size() >= 3) {
                String dim = AnnotationRenderer.lastDimension();
                GuiMapHooks.ViewState view = AnnotationRenderer.lastView();
                if (dim != null && view != null) {
                    beginLabel(TacAnnotation.ShapeType.POLYGON, dim, view,
                            AnnotationRenderer.lastGuiW(), AnnotationRenderer.lastGuiH(),
                            cursorScreenX(), cursorScreenY());
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static double cursorScreenX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() / mc.getWindow().getGuiScale();
    }

    private static double cursorScreenY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() / mc.getWindow().getGuiScale();
    }

    /** Typed characters while the label editor is active. */
    public static boolean onCharTyped(char ch) {
        if (!awaitingLabel) {
            return false;
        }
        if (ch >= 32 && ch != 127 && labelBuf.length() < TacAnnotationLabelLimit) {
            labelBuf.append(ch);
            return true;
        }
        return true;
    }

    private static final int TacAnnotationLabelLimit = 24;

    private static void confirmLabel() {
        if (pendingAnno != null) {
            String label = labelBuf.toString().trim();
            if (!label.isEmpty()) {
                if (label.length() > TacAnnotationLabelLimit) {
                    label = label.substring(0, TacAnnotationLabelLimit);
                }
                TacAnnotation named = new TacAnnotation(pendingAnno.id, pendingAnno.shape,
                        pendingAnno.symbol, pendingAnno.dimension, pendingAnno.xs, pendingAnno.zs,
                        pendingAnno.radius, label, pendingAnno.creator, pendingAnno.creatorName);
                pendingAnno = named;
            }
            TacNet.CHANNEL.sendToServer(new TacNet.AddMarkerPkt(pendingAnno));
        }
        pendingAnno = null;
        awaitingLabel = false;
        labelBuf.setLength(0);
        if (tool == Tool.POINT) {
            // stay in point mode for rapid multi-placement
        } else {
            tool = Tool.NONE;
        }
    }

    private static void cancelLabel() {
        pendingAnno = null;
        awaitingLabel = false;
        labelBuf.setLength(0);
    }

    /** Lang key suffix for the current tool (tooltips). */
    public static String toolKey(Tool t) {
        return "xaerotacmap.tool." + t.name().toLowerCase(Locale.ROOT);
    }
}
