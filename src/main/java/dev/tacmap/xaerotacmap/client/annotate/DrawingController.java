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
    private static TacAnnotation.Symbol pointSymbol = TacAnnotation.Symbol.TARGET;
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
                    if (pending.size() < 128) {
                        pending.add(new double[]{mapX, mapZ});
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

    private static boolean nearFirst(double mouseX, double mouseY,
                                     GuiMapHooks.ViewState view, int guiW, int guiH) {
        if (pending.isEmpty()) {
            return false;
        }
        double[] first = pending.get(0);
        int div = cursorDiv;
        double sx = view.toScreenX(first[0], guiW);
        double sy = view.toScreenY(first[1], guiH);
        double dx = mouseX - sx;
        double dy = mouseY - sy;
        return dx * dx + dy * dy <= 100.0D; // 10 gui px
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
        double[] xs = new double[mapVerts.length];
        double[] zs = new double[mapVerts.length];
        double radius = 0.0D;
        if (shape == TacAnnotation.ShapeType.CIRCLE) {
            xs[0] = mapVerts[0] * div;
            zs[0] = mapVerts[1] * div;
            radius = mapVerts[2];
        } else {
            for (int i = 0; i < mapVerts.length; i++) {
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
