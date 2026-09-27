package dev.tacmap.xaerotacmap.client.annotate;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;

/**
 * Minimal immediate-mode shape kit for the annotation overlay. Everything is
 * emitted as POSITION_COLOR triangles (thin quads for strokes), drawn with the
 * position-color shader in gui-screen space.
 *
 * <p>ARGB colors are converted to the ABGR order the vertex format expects.</p>
 */
public final class ShapeDraw {

    private ShapeDraw() {
    }

    /** GL cull state captured by {@link #beginFrame} and restored by {@link #endFrame}. */
    private static boolean prevCull;

    /**
     * Sets up blend + no-depth + shader + NO CULL; call before issuing shape
     * commands.
     *
     * <p>The cull disable is CRITICAL: Xaero's map rendering leaves back-face
     * culling enabled on the GL state, and our vertices are emitted in mixed
     * winding orders (circles/arrows fan one way, some fills the other). With
     * cull on, every triangle whose winding disagrees with the front face is
     * silently discarded by the GPU - the "missing icon parts" bug (palette
     * dots, route/circle arrowheads, marker center dots vanished).</p>
     */
    public static void beginFrame() {
        prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    public static void endFrame() {
        RenderSystem.enableDepthTest();
        if (prevCull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
    }

    public static void vertex(BufferBuilder bb, PoseStack.Pose pose, double x, double y, int abgr) {
        bb.vertex(pose.pose(), (float) x, (float) y, 0.0F)
                .color((abgr >> 16) & 0xFF, (abgr >> 8) & 0xFF, abgr & 0xFF, (abgr >> 24) & 0xFF)
                .endVertex();
    }

    /** Converts 0xAARRGGBB to the byte order the color loader expects. */
    public static int abgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // ------------------------------------------------------------ batched helpers
    // Each helper expects an open TRIANGLES BufferBuilder.

    public static void fillTri(BufferBuilder bb, PoseStack.Pose pose,
                               double x0, double y0, double x1, double y1, double x2, double y2,
                               int abgrColor) {
        vertex(bb, pose, x0, y0, abgrColor);
        vertex(bb, pose, x1, y1, abgrColor);
        vertex(bb, pose, x2, y2, abgrColor);
    }

    /**
     * Robust fill of a simple polygon via ear-clipping triangulation.
     *
     * <p>The old implementation fanned every triangle from vertex 0, which
     * only renders correct results for CONVEX rings: concave polygons spill
     * outside their own outline and self-intersecting ones (the "butterfly"
     * mis-click case) paint the wrong lobe entirely. Ear clipping handles any
     * simple polygon; worst case O(n^2) with n &lt;= 128 vertices is trivial
     * for a per-frame overlay.</p>
     */
    public static void fillPoly(BufferBuilder bb, PoseStack.Pose pose,
                                double[] xs, double[] ys, int abgrColor) {
        int n = xs.length;
        if (n < 3) {
            return;
        }
        // Signed area fixes the ring orientation and catches degenerate
        // (zero-area / collinear) rings before any triangle is emitted.
        double area2 = 0.0D;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area2 += xs[i] * ys[j] - xs[j] * ys[i];
        }
        if (Math.abs(area2) < 1.0E-9D) {
            return; // degenerate (zero-area) ring
        }
        if (n == 3) {
            fillTri(bb, pose, xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], abgrColor);
            return;
        }
        boolean cw = area2 < 0.0D; // screen-space orientation (y down)
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        int remaining = n;
        int guard = 0;
        int at = 0;
        while (remaining > 3 && guard++ < n * n) {
            int i0 = idx[at % remaining];
            int i1 = idx[(at + 1) % remaining];
            int i2 = idx[(at + 2) % remaining];
            double ax = xs[i0], ay = ys[i0];
            double bx = xs[i1], by = ys[i1];
            double cx = xs[i2], cy = ys[i2];
            if (isConvex(ax, ay, bx, by, cx, cy, cw)
                    && noPointInTriangle(idx, remaining, at, ax, ay, bx, by, cx, cy, xs, ys)) {
                fillTri(bb, pose, ax, ay, bx, by, cx, cy, abgrColor);
                // remove the ear tip (middle vertex) and step back one slot
                int tip = (at + 1) % remaining;
                for (int k = tip; k + 1 < remaining; k++) {
                    idx[k] = idx[k + 1];
                }
                remaining--;
                at = Math.max(0, at - 1);
            } else {
                at = (at + 1) % remaining;
            }
        }
        if (remaining == 3) {
            fillTri(bb, pose, xs[idx[0]], ys[idx[0]], xs[idx[1]], ys[idx[1]],
                    xs[idx[2]], ys[idx[2]], abgrColor);
        }
    }

    /** Convex corner test matched to the ring orientation (screen space, y down). */
    private static boolean isConvex(double ax, double ay, double bx, double by,
                                    double cx, double cy, boolean cw) {
        double cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
        return cw ? cross < 0.0D : cross > 0.0D;
    }

    /** Whether any OTHER vertex of the active ring lies inside the candidate ear. */
    private static boolean noPointInTriangle(int[] idx, int remaining, int at,
                                             double ax, double ay, double bx, double by,
                                             double cx, double cy,
                                             double[] xs, double[] ys) {
        for (int k = 0; k < remaining; k++) {
            if (k == at || k == (at + 1) % remaining || k == (at + 2) % remaining) {
                continue;
            }
            int vi = idx[k];
            if (pointInTri(xs[vi], ys[vi], ax, ay, bx, by, cx, cy)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInTri(double px, double py,
                                      double ax, double ay, double bx, double by,
                                      double cx, double cy) {
        double d0 = (px - ax) * (by - ay) - (py - ay) * (bx - ax);
        double d1 = (px - bx) * (cy - by) - (py - by) * (cx - bx);
        double d2 = (px - cx) * (ay - cy) - (py - cy) * (ax - ax);
        boolean neg = d0 < 0.0D || d1 < 0.0D || d2 < 0.0D;
        boolean pos = d0 > 0.0D || d1 > 0.0D || d2 > 0.0D;
        return !(neg && pos);
    }

    /**
     * Segment-segment intersection test used to reject polygon vertices that
     * would make the ring self-intersect (the "butterfly" selection bug).
     */
    public static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                            double cx, double cy, double dx, double dy) {
        double d1 = cross(ax, ay, bx, by, cx, cy);
        double d2 = cross(ax, ay, bx, by, dx, dy);
        double d3 = cross(cx, cy, dx, dy, ax, ay);
        double d4 = cross(cx, cy, dx, dy, bx, by);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        // collinear touch counts as intersecting too (degenerate overlaps)
        return onSeg(ax, ay, bx, by, cx, cy) || onSeg(ax, ay, bx, by, dx, dy)
                || onSeg(cx, cy, dx, dy, ax, ay) || onSeg(cx, cy, dx, dy, bx, by);
    }

    private static double cross(double ax, double ay, double bx, double by,
                                double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static boolean onSeg(double ax, double ay, double bx, double by,
                                 double px, double py) {
        double EPS = 1.0E-9D;
        return Math.abs(cross(ax, ay, bx, by, px, py)) < EPS
                && Math.min(ax, bx) - EPS <= px && px <= Math.max(ax, bx) + EPS
                && Math.min(ay, by) - EPS <= py && py <= Math.max(ay, by) + EPS;
    }

    /** One segment as a thin quad extended by halfW on both ends (joint overlap). */
    public static void seg(BufferBuilder bb, PoseStack.Pose pose,
                           double ax, double ay, double bx, double by, double halfW, int abgrColor) {
        double dx = bx - ax;
        double dy = by - ay;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-6D) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double px = -uy * halfW;
        double py = ux * halfW;
        double ex = ux * halfW;
        double ey = uy * halfW;
        vertex(bb, pose, ax - ex + px, ay - ey + py, abgrColor);
        vertex(bb, pose, bx + ex + px, by + ey + py, abgrColor);
        vertex(bb, pose, bx + ex - px, by + ey - py, abgrColor);
        vertex(bb, pose, ax - ex + px, ay - ey + py, abgrColor);
        vertex(bb, pose, bx + ex - px, by + ey - py, abgrColor);
        vertex(bb, pose, ax - ex - px, ay - ey - py, abgrColor);
    }

    public static void strokePolyline(BufferBuilder bb, PoseStack.Pose pose,
                                      double[] xs, double[] ys, double halfW, boolean closed,
                                      int abgrColor) {
        for (int i = 0; i + 1 < xs.length; i++) {
            seg(bb, pose, xs[i], ys[i], xs[i + 1], ys[i + 1], halfW, abgrColor);
        }
        if (closed && xs.length > 2) {
            seg(bb, pose, xs[xs.length - 1], ys[ys.length - 1], xs[0], ys[0], halfW, abgrColor);
        }
    }

    public static void strokeDashed(BufferBuilder bb, PoseStack.Pose pose,
                                    double[] xs, double[] ys, double halfW, boolean closed,
                                    double dash, double gap, int abgrColor) {
        double pattern = dash + gap;
        for (int i = 0; i + 1 < xs.length; i++) {
            dashedSeg(bb, pose, xs[i], ys[i], xs[i + 1], ys[i + 1], halfW, dash, pattern, abgrColor);
        }
        if (closed && xs.length > 2) {
            dashedSeg(bb, pose, xs[xs.length - 1], ys[ys.length - 1], xs[0], ys[0],
                    halfW, dash, pattern, abgrColor);
        }
    }

    private static void dashedSeg(BufferBuilder bb, PoseStack.Pose pose,
                                  double ax, double ay, double bx, double by,
                                  double halfW, double dash, double pattern, int abgrColor) {
        double dx = bx - ax;
        double dy = by - ay;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-6D) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double d = 0.0D;
        while (d < len) {
            double dEnd = Math.min(d + dash, len);
            seg(bb, pose, ax + ux * d, ay + uy * d, ax + ux * dEnd, ay + uy * dEnd, halfW, abgrColor);
            d += pattern;
        }
    }

    public static void fillCircle(BufferBuilder bb, PoseStack.Pose pose,
                                  double cx, double cy, double r, int abgrColor, int segments) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0D * i / segments;
            double a1 = Math.PI * 2.0D * (i + 1) / segments;
            fillTri(bb, pose, cx, cy,
                    cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
                    cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, abgrColor);
        }
    }

    public static void ring(BufferBuilder bb, PoseStack.Pose pose,
                            double cx, double cy, double r, double halfW, int abgrColor, int segments) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0D * i / segments;
            double a1 = Math.PI * 2.0D * (i + 1) / segments;
            seg(bb, pose,
                    cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
                    cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, halfW, abgrColor);
        }
    }

    /** Triangle arrow head pointing along (ux,uy) with tip at (tx,ty). */
    public static void arrow(BufferBuilder bb, PoseStack.Pose pose,
                             double tx, double ty, double ux, double uy,
                             double len, double halfBase, int abgrColor) {
        double px = -uy;
        double py = ux;
        fillTri(bb, pose,
                tx, ty,
                tx - ux * len + px * halfBase, ty - uy * len + py * halfBase,
                tx - ux * len - px * halfBase, ty - uy * len - py * halfBase,
                abgrColor);
    }

    public static BufferBuilder open() {
        // self-contained render state: toolbar/palette draw OUTSIDE the
        // beginFrame block, where depth/shader state is whatever vanilla left
        beginFrame();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        return bb;
    }

    public static void flush(BufferBuilder bb) {
        BufferUploader.drawWithShader(bb.end());
    }
}
