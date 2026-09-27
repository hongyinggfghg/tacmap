package dev.tacmap.xaerotacmap.client.annotate;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;

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

    /** Sets up blend + no-depth + shader; call before issuing shape commands. */
    public static void beginFrame() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    public static void endFrame() {
        RenderSystem.enableDepthTest();
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

    /** Triangle-fan fill of a convex/concave polygon (fan from vertex 0). */
    public static void fillPoly(BufferBuilder bb, PoseStack.Pose pose,
                                double[] xs, double[] ys, int abgrColor) {
        for (int i = 1; i + 1 < xs.length; i++) {
            fillTri(bb, pose, xs[0], ys[0], xs[i], ys[i], xs[i + 1], ys[i + 1], abgrColor);
        }
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
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        return bb;
    }

    public static void flush(BufferBuilder bb) {
        BufferUploader.drawWithShader(bb.end());
    }
}
