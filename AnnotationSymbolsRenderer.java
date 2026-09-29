package dev.tacmap.xaerotacmap.client.annotate;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.tacmap.xaerotacmap.annotation.TacAnnotation.Symbol;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the tactical symbol pack as small gui-space icons centered on a point.
 * Letters are stamped with the vanilla font on top of the primitive batch.
 *
 * <p>The palette is split into TWO squads (v4.0.5): friendly markers rendered
 * with the squad color and enemy markers rendered always-red. The basic
 * geometric shapes (dot/square/diamond/triangle) were REMOVED from the palette
 * on request - the enum values and their draw branches stay for backwards
 * compatibility with old network data and export files, they are just not
 * selectable any more.</p>
 */
public final class AnnotationSymbolsRenderer {

    /**
     * Friendly-marker palette (upper section): renders with the squad color.
     * Geometric noise (dot/square/diamond/triangle) dropped, star kept.
     */
    public static final Symbol[] ALLY = {
            Symbol.RALLY, Symbol.OBS, Symbol.CP, Symbol.SUPPLY, Symbol.MEDIC,
            Symbol.AMMO, Symbol.LZ, Symbol.DZ, Symbol.BASE, Symbol.CHECKPOINT,
            Symbol.FLAG, Symbol.GEO_STAR
    };

    /**
     * Enemy-marker palette (lower section): threat / hostile intel symbols
     * plus the v4.0.6 objective marks (enemy rally / enemy base / enemy
     * star), always rendered red on the map.
     */
    public static final Symbol[] ENEMY = {
            Symbol.ENEMY, Symbol.SUSPECT, Symbol.AMBUSH, Symbol.SNIPER, Symbol.TARGET,
            Symbol.ENEMY_RALLY, Symbol.ENEMY_BASE, Symbol.ENEMY_STAR
    };

    /** Palette display order: friendly pack first, then the enemy pack. */
    public static final Symbol[] ALL;

    static {
        ALL = new Symbol[ALLY.length + ENEMY.length];
        System.arraycopy(ALLY, 0, ALL, 0, ALLY.length);
        System.arraycopy(ENEMY, 0, ALL, ALLY.length, ENEMY.length);
    }

    /**
     * Whether this symbol belongs to the enemy (always-red) family.
     * v4.0.10: {@link Symbol#ENEMY_ROUTE} is hostile too, but it is NOT in
     * the {@link #ENEMY} point-palette array (it is a route symbol picked
     * via its own toolbar tool), so it gets an explicit check here.
     */
    public static boolean isHostile(Symbol s) {
        if (s == Symbol.ENEMY_ROUTE) {
            return true;
        }
        for (Symbol e : ENEMY) {
            if (e == s) {
                return true;
            }
        }
        return false;
    }

    private AnnotationSymbolsRenderer() {
    }

    /**
     * Draws one symbol centered at (cx, cy) in gui coords.
     *
     * @param half nominal half size in gui px (icons are drawn ~2x this)
     */
    public static void draw(GuiGraphics gg, Font font, double cx, double cy,
                            Symbol s, int argb, double half) {
        if (s == null) {
            s = Symbol.GEO_CIRCLE;
        }
        PoseStack.Pose pose = gg.pose().last();
        int abgr = ShapeDraw.abgr(argb);
        int white = ShapeDraw.abgr(0xFFFFFFFF);
        int black = ShapeDraw.abgr(0xE0202020);

        BufferBuilder bb = ShapeDraw.open();
        drawBatched(bb, pose, cx, cy, s, abgr, half);
        ShapeDraw.flush(bb);

        // letter stamps on top (per-symbol)
        String stamp = stampOf(s);
        if (stamp != null) {
            float w = font.width(stamp);
            // letter scale follows the icon size, clamped to stay readable
            float k = (float) Math.max(0.4D, Math.min(1.1D, half / 7.0D));
            float scale = (stamp.length() > 1 ? 0.6F : 0.7F) * k;
            float tx = (float) cx - w * scale / 2.0F;
            float ty = (float) cy - 4.0F * scale;
            gg.pose().pushPose();
            gg.pose().translate(tx, ty, 0);
            gg.pose().scale(scale, scale, 1.0F);
            gg.drawString(font, stamp, 0, 0, filled(s) ? 0xFF202020 : 0xFFFFFFFF, false);
            gg.pose().popPose();
        }
    }

    private static boolean filled(Symbol s) {
        switch (s) {
            case ENEMY: case BASE: case ENEMY_BASE: case GEO_CIRCLE:
            case GEO_SQUARE: case GEO_DIAMOND: case GEO_TRIANGLE:
            case GEO_STAR: case ENEMY_STAR: case MEDIC:
                return true;
            default:
                return false;
        }
    }

    /** The text stamped over the icon, or null when the icon has no text. */
    private static String stampOf(Symbol s) {
        switch (s) {
            case CP: return "C";
            case SUPPLY: return "S";
            case AMMO: return "A";
            case BASE: case ENEMY_BASE: return "B";
            case CHECKPOINT: return "C";
            case LZ: return "L";
            case DZ: return "D";
            case OBS: return "O";
            case ENEMY: return "!";
            case SUSPECT: return "?";
            default: return null;
        }
    }

    private static void drawBatched(BufferBuilder bb, PoseStack.Pose pose,
                                    double cx, double cy, Symbol s, int abgr, double h) {
        double lw = 0.8D; // stroke half width
        switch (s) {
            case TARGET: {
                ShapeDraw.ring(bb, pose, cx, cy, h * 0.85D, lw, abgr, 20);
                ShapeDraw.seg(bb, pose, cx - h * 1.2D, cy, cx - h * 0.45D, cy, lw, abgr);
                ShapeDraw.seg(bb, pose, cx + h * 0.45D, cy, cx + h * 1.2D, cy, lw, abgr);
                ShapeDraw.seg(bb, pose, cx, cy - h * 1.2D, cx, cy - h * 0.45D, lw, abgr);
                ShapeDraw.seg(bb, pose, cx, cy + h * 0.45D, cx, cy + h * 1.2D, lw, abgr);
                ShapeDraw.fillCircle(bb, pose, cx, cy, h * 0.14D, abgr, 8);
                break;
            }
            case RALLY: case ENEMY_RALLY: {
                // flag on a pole (enemy variant differs by its red color)
                ShapeDraw.seg(bb, pose, cx - h * 0.55D, cy - h, cx - h * 0.55D, cy + h, lw, abgr);
                double fx = cx - h * 0.55D;
                double[] fx3 = {fx, fx + h * 1.5D, fx};
                double[] fy3 = {cy - h, cy - h * 0.55D, cy - h * 0.1D};
                ShapeDraw.fillPoly(bb, pose, fx3, fy3, abgr);
                break;
            }
            case AMBUSH: {
                ShapeDraw.seg(bb, pose, cx - h, cy - h, cx + h, cy + h, lw, abgr);
                ShapeDraw.seg(bb, pose, cx - h, cy + h, cx + h, cy - h, lw, abgr);
                break;
            }
            case SNIPER: {
                ShapeDraw.ring(bb, pose, cx, cy, h * 0.55D, lw * 0.8D, abgr, 14);
                ShapeDraw.seg(bb, pose, cx - h * 1.1D, cy, cx + h * 1.1D, cy, lw * 0.8D, abgr);
                ShapeDraw.seg(bb, pose, cx, cy - h * 1.1D, cx, cy + h * 1.1D, lw * 0.8D, abgr);
                break;
            }
            case OBS: {
                ShapeDraw.ring(bb, pose, cx, cy, h * 0.85D, lw, abgr, 18);
                ShapeDraw.fillCircle(bb, pose, cx, cy, h * 0.18D, abgr, 8);
                break;
            }
            case CP: case SUPPLY: case AMMO: case CHECKPOINT: {
                double q = h * 0.85D;
                quadOutline(bb, pose, cx - q, cy - q, 2 * q, 2 * q, lw, abgr);
                if (s == Symbol.CHECKPOINT) {
                    // diagonal slash to differ from CP
                    ShapeDraw.seg(bb, pose, cx - q, cy + q, cx + q, cy - q, lw * 0.7D, abgr);
                }
                break;
            }
            case MEDIC: {
                double q = h * 0.85D;
                double[] xs = {cx - q, cx + q, cx + q, cx - q};
                double[] ys = {cy - q, cy - q, cy + q, cy + q};
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                double cw = q * 0.34D;
                double cl = q * 0.72D;
                // white cross on top
                ShapeDraw.seg(bb, pose, cx, cy - cl, cx, cy + cl, cw, ShapeDraw.abgr(0xFFFFFFFF));
                ShapeDraw.seg(bb, pose, cx - cl, cy, cx + cl, cy, cw, ShapeDraw.abgr(0xFFFFFFFF));
                break;
            }
            case LZ: case DZ: {
                ShapeDraw.ring(bb, pose, cx, cy, h * 0.95D, lw, abgr, 20);
                break;
            }
            case BASE: case ENEMY_BASE: {
                double q = h * 0.85D;
                double[] xs = {cx - q, cx + q, cx + q, cx - q};
                double[] ys = {cy - q, cy - q, cy + q, cy + q};
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                quadOutline(bb, pose, cx - q, cy - q, 2 * q, 2 * q, lw, ShapeDraw.abgr(0xFF101418));
                break;
            }
            case ENEMY: case SUSPECT: {
                // diamond
                double d = h * 1.05D;
                double[] xs = {cx, cx + d, cx, cx - d};
                double[] ys = {cy - d, cy, cy + d, cy};
                if (s == Symbol.ENEMY) {
                    ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                    ShapeDraw.strokePolyline(bb, pose, xs, ys, lw, true, ShapeDraw.abgr(0xFF7A1010));
                } else {
                    ShapeDraw.strokePolyline(bb, pose, xs, ys, lw, true, abgr);
                }
                break;
            }
            case FLAG: {
                ShapeDraw.seg(bb, pose, cx - h * 0.55D, cy - h, cx - h * 0.55D, cy + h, lw, abgr);
                double fx = cx - h * 0.55D;
                double fy = cy - h;
                double[] xs = {fx, fx + h * 1.5D, fx + h * 1.2D, fx + h * 1.5D, fx};
                double[] ys = {fy, fy + h * 0.25D, fy + h * 0.55D, fy + h * 0.85D, fy + h * 0.9D};
                ShapeDraw.strokePolyline(bb, pose, xs, ys, lw * 0.8D, false, abgr);
                break;
            }
            case GEO_CIRCLE: {
                ShapeDraw.fillCircle(bb, pose, cx, cy, h * 0.8D, abgr, 18);
                break;
            }
            case GEO_SQUARE: {
                double q = h * 0.72D;
                double[] xs = {cx - q, cx + q, cx + q, cx - q};
                double[] ys = {cy - q, cy - q, cy + q, cy + q};
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                break;
            }
            case GEO_DIAMOND: {
                double d = h * 0.95D;
                double[] xs = {cx, cx + d, cx, cx - d};
                double[] ys = {cy - d, cy, cy + d, cy};
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                break;
            }
            case GEO_TRIANGLE: {
                double r = h * 1.0D;
                double[] xs = {cx, cx + r * 0.95D, cx - r * 0.95D};
                double[] ys = {cy - r, cy + r * 0.75D, cy + r * 0.75D};
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                break;
            }
            case GEO_STAR: case ENEMY_STAR: {
                double ro = h * 1.05D;
                double ri = ro * 0.42D;
                double[] xs = new double[10];
                double[] ys = new double[10];
                for (int i = 0; i < 10; i++) {
                    double ang = -Math.PI / 2.0D + Math.PI * i / 5.0D;
                    double r = (i % 2 == 0) ? ro : ri;
                    xs[i] = cx + Math.cos(ang) * r;
                    ys[i] = cy + Math.sin(ang) * r;
                }
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
                break;
            }
            default:
                ShapeDraw.fillCircle(bb, pose, cx, cy, h * 0.8D, abgr, 14);
                break;
        }
    }

    private static void quadOutline(BufferBuilder bb, PoseStack.Pose pose,
                                    double x, double y, double w, double hgt,
                                    double lw, int abgr) {
        double[] xs = {x, x + w, x + w, x};
        double[] ys = {y, y, y + hgt, y + hgt};
        ShapeDraw.strokePolyline(bb, pose, xs, ys, lw, true, abgr);
    }
}
