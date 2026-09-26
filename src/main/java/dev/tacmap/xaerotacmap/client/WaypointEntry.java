package dev.tacmap.xaerotacmap.client;

/**
 * Immutable snapshot of one waypoint relevant to the tactical HUD.
 * All heavy Xaero types are resolved before constructing this, so the HUD
 * renderer never touches Xaero classes directly.
 */
public final class WaypointEntry {

    /** Display name (already localized by Xaero when possible). */
    public final String name;
    /** Waypoint symbol string, may be empty. */
    public final String symbol;
    /** Waypoint color as 0xRRGGBB. */
    public final int colorRgb;
    /** Real world coordinates of the waypoint. */
    public final int x;
    public final int y;
    public final int z;
    /** False when the waypoint has no meaningful Y (2D waypoint). */
    public final boolean yIncluded;
    /** Distance from the player to this waypoint. */
    public final double distance;
    /** Absolute bearing (north = 0) from the player to this waypoint. */
    public final double bearing;
    /** y - playerY, only meaningful when yIncluded. */
    public final double yDiff;

    public WaypointEntry(String name, String symbol, int colorRgb,
                         int x, int y, int z, boolean yIncluded,
                         double distance, double bearing, double yDiff) {
        this.name = name;
        this.symbol = symbol;
        this.colorRgb = colorRgb;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yIncluded = yIncluded;
        this.distance = distance;
        this.bearing = bearing;
        this.yDiff = yDiff;
    }
}
