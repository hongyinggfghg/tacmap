package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v4.0.12: feeds the squad's map annotations into the K-key HUD ("战术目标").
 *
 * <p>Before this class the HUD listed only Xaero waypoints, so the targets a
 * squad actually draws (target marks, enemy contacts, axis-of-advance routes)
 * never appeared with their distance/bearing/facing readouts. Every
 * annotation contributes ONE row anchored at a representative point:</p>
 *
 * <ul>
 *   <li>POINT - the point itself.</li>
 *   <li>ROUTE / ENEMY_ROUTE - the LAST vertex, i.e. where the arrowhead
 *       points: the objective the route leads to / the axis is advancing on.</li>
 *   <li>POLYGON - the centroid of its vertices.</li>
 *   <li>CIRCLE - the center (the rim is rarely the actionable spot).</li>
 * </ul>
 *
 * <p>Annotations are 2D (no stored Y), so distance is 2D exactly like the map
 * renderer computes it. Dot colors follow the v4.0.10 strict two-tone rule
 * ({@link ClientMarkerStore#renderColor}): friendly blue, hostile red.
 * Unnamed annotations fall back to the same localized defaults the drawing
 * tool uses (symbol name / 路线 / 区域 / 敌路).</p>
 *
 * <p>This class never throws: any failure is logged once at debug level and
 * yields an empty list, so the HUD keeps working with waypoints alone.</p>
 */
public final class AnnotationHudSource {

    private AnnotationHudSource() {
    }

    /** Builds HUD rows for the annotations of one dimension (never null). */
    public static List<WaypointEntry> collect(double px, double pz, String dimension) {
        List<WaypointEntry> out = new ArrayList<>();
        if (!TacMapConfig.HUD_ANNOTATIONS.get()) {
            return out;
        }
        try {
            for (TacAnnotation a : ClientMarkerStore.markersIn(dimension)) {
                if (a == null || a.xs.length == 0 || a.xs.length != a.zs.length) {
                    continue;
                }
                double tx;
                double tz;
                switch (a.shape) {
                    case ROUTE -> {
                        // includes ENEMY_ROUTE: aim at the arrowhead end
                        tx = a.xs[a.xs.length - 1];
                        tz = a.zs[a.zs.length - 1];
                    }
                    case POLYGON -> {
                        double sx = 0.0D;
                        double sz = 0.0D;
                        for (int i = 0; i < a.xs.length; i++) {
                            sx += a.xs[i];
                            sz += a.zs[i];
                        }
                        tx = sx / a.xs.length;
                        tz = sz / a.zs.length;
                    }
                    // POINT and CIRCLE (center) share the first-vertex anchor
                    default -> {
                        tx = a.xs[0];
                        tz = a.zs[0];
                    }
                }

                double dx = tx - px;
                double dz = tz - pz;
                double distance = BearingMath.distance2D(dx, dz);
                double bearing = BearingMath.bearingDeg(px, pz, tx, tz);
                double yaw = BearingMath.yawDeg(px, pz, tx, tz);

                String name = a.label == null || a.label.isEmpty() ? defaultName(a) : a.label;

                // renderColor() is ARGB; WaypointEntry stores 0xRRGGBB.
                int rgb = ClientMarkerStore.renderColor(a) & 0xFFFFFF;

                out.add(new WaypointEntry(name, "", rgb,
                        (int) Math.round(tx), 0, (int) Math.round(tz), false,
                        distance, bearing, yaw, 0.0D));
            }
        } catch (Throwable t) {
            // Never let annotation state break the HUD - waypoints still render.
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.tickCount % 200 == 0) {
                XaeroTacMap.LOGGER.debug("[TacMap] Annotation HUD collection failed", t);
            }
        }
        return out;
    }

    /** Unnamed annotations fall back to the same defaults the map tool uses. */
    private static String defaultName(TacAnnotation a) {
        if (a.shape == TacAnnotation.ShapeType.ROUTE) {
            return Component.translatable(
                    a.symbol == TacAnnotation.Symbol.ENEMY_ROUTE
                            ? "xaerotacmap.annotate.default_enemy_route"
                            : "xaerotacmap.annotate.default_route").getString();
        }
        if (a.shape == TacAnnotation.ShapeType.POLYGON
                || a.shape == TacAnnotation.ShapeType.CIRCLE) {
            return Component.translatable("xaerotacmap.annotate.default_area").getString();
        }
        return Component.translatable(a.symbol.langKey()).getString();
    }
}
