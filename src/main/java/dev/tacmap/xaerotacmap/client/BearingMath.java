package dev.tacmap.xaerotacmap.client;

import java.util.Locale;

/**
 * Bearing / distance math shared by the HUD and the world map overlay.
 *
 * <p>Two bearing conventions coexist everywhere (v4.0.7 "degree mode"):
 * <ul>
 *   <li>{@link #bearingDeg} - absolute compass bearing: north = 0, east = 90,
 *       south = 180, west = 270, range [0, 360). Independent of where the
 *       player is looking (matches a compass rose on a tactical map).</li>
 *   <li>{@link #yawDeg} - Minecraft yaw (the F3 facing readout): south = 0,
 *       west = +90, north = 180, east = -90, range (-180, 180]. This is the
 *       number a player can dial in on their own view, so it is the primary
 *       readout for fire-calling.</li>
 * </ul>
 * Conversion: compass = (180 + yaw) mod 360, both directions kept on screen.</p>
 */
public final class BearingMath {

    private BearingMath() {
    }

    /**
     * Absolute bearing in degrees [0, 360) from (px, pz) to (tx, tz).
     * North is -Z (Minecraft north), east is +X.
     */
    public static double bearingDeg(double px, double pz, double tx, double tz) {
        double dx = tx - px;
        double dz = tz - pz;
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0.0D) {
            deg += 360.0D;
        }
        // Guard against atan2 returning exactly 360-ish due to floating point.
        deg %= 360.0D;
        return deg < 0.0D ? deg + 360.0D : deg;
    }

    /**
     * Minecraft yaw (F3 facing) bearing from (px, pz) to (tx, tz), per the
     * squad fire-calling spec: south = 0, west = +90, north = 180, east = -90.
     * Range (-180, 180]; due north always comes out as +180, never -180.
     */
    public static double yawDeg(double px, double pz, double tx, double tz) {
        double dx = tx - px;
        double dz = tz - pz;
        double deg = Math.toDegrees(Math.atan2(-dx, dz));
        deg %= 360.0D;
        if (deg <= -180.0D) {
            deg += 360.0D;
        }
        if (deg > 180.0D) {
            deg -= 360.0D;
        }
        return deg;
    }

    /**
     * Rounds a yaw to a whole degree for the integer readouts (annotation
     * chips) and maps the -180 edge to +180 so north always prints as 180
     * like the squad spec table requires.
     */
    public static int yawRound(double yaw) {
        int n = (int) Math.round(yaw) % 360;
        if (n <= -180) {
            n += 360;
        }
        if (n > 180) {
            n -= 360;
        }
        return n;
    }

    /** Compass bearing [0, 360) -> yaw convention (-180, 180]. */
    public static double compassToYaw(double compass) {
        double yaw = (compass - 180.0D) % 360.0D;
        if (yaw <= -180.0D) {
            yaw += 360.0D;
        }
        if (yaw > 180.0D) {
            yaw -= 360.0D;
        }
        return yaw;
    }

    /** Yaw convention (-180, 180] -> compass bearing [0, 360). */
    public static double yawToCompass(double yaw) {
        double c = (yaw + 180.0D) % 360.0D;
        return c < 0.0D ? c + 360.0D : c;
    }

    /**
     * Index into the 8-way compass: 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW.
     */
    public static int dirIndex(double bearingDeg) {
        double b = ((bearingDeg % 360.0D) + 360.0D) % 360.0D;
        return (int) Math.round(b / 45.0D) % 8;
    }

    private static final String[] DIR_KEYS = {
            "xaerotacmap.dir.n", "xaerotacmap.dir.ne", "xaerotacmap.dir.e", "xaerotacmap.dir.se",
            "xaerotacmap.dir.s", "xaerotacmap.dir.sw", "xaerotacmap.dir.w", "xaerotacmap.dir.nw"
    };

    /** Translation key of the 8-way direction label for the given bearing. */
    public static String dirKey(double bearingDeg) {
        return DIR_KEYS[dirIndex(bearingDeg)];
    }

    /** Fixed-decimal formatting, e.g. 123.5. */
    public static String fmt(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", value);
    }

    /** Rounds a value to the given number of decimals (used for Y-difference display). */
    public static double roundTo(double value, int decimals) {
        double f = Math.pow(10.0D, Math.max(0, decimals));
        return Math.round(value * f) / f;
    }

    /** Distance between two 3D points. */
    public static double distance3D(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Horizontal (2D) distance. */
    public static double distance2D(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** ARGB int -> ABGR int as expected by the POSITION_COLOR vertex format. */
    public static int argbToAbgr(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int bl = argb & 0xFF;
        return (a << 24) | (bl << 16) | (g << 8) | r;
    }

    /** Fully opaque ARGB color for the given 0xRRGGBB color. */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }
}
