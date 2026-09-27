package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.config.TacMapConfig;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointSet;
import xaero.common.minimap.waypoints.WaypointsManager;
import xaero.hud.minimap.waypoint.WaypointPurpose;

import java.util.ArrayList;
import java.util.List;

/**
 * Integration with Xaero's Minimap waypoint storage.
 *
 * <p><b>This class must only be loaded when {@code XaeroBridge.minimapPresent()}
 * is true</b> - it directly references Xaero Minimap classes, which are provided
 * as compile-only dependencies and expected to exist at runtime.</p>
 *
 * <p>Data chain (verified against xaerominimap 26.x for MC 1.20.1):
 * <pre>
 * XaeroMinimapSession.getCurrentSession()          (nullable, only in world)
 *   .getWaypointsManager()                          WaypointsManager
 *   .getWaypoints()                                 WaypointSet (current world, current set; nullable)
 *   .getList()                                      ArrayList&lt;Waypoint&gt;
 * </pre></p>
 */
public final class XaeroWaypointSource {

    /**
     * Human-readable state of the last collection pass, used by the HUD
     * status panel: session_null / manager_null / set_null / empty / ok / exception.
     * Only touched while the minimap is present (this class is not loaded otherwise).
     */
    public static volatile String lastStatus = "session_null";

    private XaeroWaypointSource() {
    }

    /**
     * Collects all currently visible waypoints of the active waypoint set and
     * computes distance / bearing from the given player position.
     */
    public static List<WaypointEntry> collect(double px, double py, double pz) {
        List<WaypointEntry> out = new ArrayList<>();
        try {
            XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
            if (session == null) {
                lastStatus = "session_null";
                return out;
            }
            WaypointsManager manager = session.getWaypointsManager();
            if (manager == null) {
                lastStatus = "manager_null";
                return out;
            }
            WaypointSet set = manager.getWaypoints();
            if (set == null) {
                lastStatus = "set_null";
                return out;
            }
            ArrayList<Waypoint> waypoints = set.getList();
            if (waypoints == null || waypoints.isEmpty()) {
                lastStatus = "empty";
                return out;
            }

            boolean includeDisabled = TacMapConfig.INCLUDE_DISABLED.get();
            boolean includeTemporary = TacMapConfig.INCLUDE_TEMPORARY.get();
            boolean includeDeath = TacMapConfig.INCLUDE_DEATHPOINTS.get();
            int decimals = TacMapConfig.DECIMALS.get();

            for (Waypoint wp : waypoints) {
                if (wp == null) {
                    continue;
                }
                try {
                    if (!includeDisabled && wp.isDisabled()) {
                        continue;
                    }
                    if (!includeTemporary && wp.isTemporary()) {
                        continue;
                    }
                    WaypointPurpose purpose = wp.getPurpose();
                    if (!includeDeath && purpose != null
                            && (purpose == WaypointPurpose.DEATH || purpose == WaypointPurpose.OLD_DEATH)) {
                        continue;
                    }

                    int wx = wp.getX();
                    int wy = wp.getY();
                    int wz = wp.getZ();
                    boolean yIncluded = wp.isYIncluded();

                    double dx = wx - px;
                    double dz = wz - pz;
                    double dy = wy - py;
                    double distance = yIncluded
                            ? BearingMath.distance3D(dx, dy, dz)
                            : BearingMath.distance2D(dx, dz);
                    double bearing = BearingMath.bearingDeg(px, pz, wx, wz);
                    double yaw = BearingMath.yawDeg(px, pz, wx, wz);
                    double yDiff = BearingMath.roundTo(dy, decimals + 1);

                    String name = wp.getLocalizedName();
                    if (name == null || name.isEmpty()) {
                        name = wp.getName();
                    }
                    if (name == null) {
                        name = "?";
                    }

                    xaero.hud.minimap.waypoint.WaypointColor color = wp.getWaypointColor();
                    int rgb = color != null ? (color.getHex() & 0xFFFFFF) : 0xFFFFFF;
                    String symbol = wp.getSymbol();
                    if (symbol == null) {
                        symbol = "";
                    }

                    out.add(new WaypointEntry(name, symbol, rgb, wx, wy, wz, yIncluded,
                            distance, bearing, yaw, yDiff));
                } catch (Throwable t) {
                    // Never let a single odd waypoint break the whole HUD.
                    dev.tacmap.xaerotacmap.XaeroTacMap.LOGGER.debug("[TacMap] Skipped one waypoint", t);
                }
            }
            lastStatus = "ok";
        } catch (Throwable t) {
            lastStatus = "exception";
            dev.tacmap.xaerotacmap.XaeroTacMap.LOGGER.warn("[TacMap] Waypoint collection failed; "
                    + "check that your installed Xaero's Minimap version is compatible", t);
        }
        return out;
    }
}
