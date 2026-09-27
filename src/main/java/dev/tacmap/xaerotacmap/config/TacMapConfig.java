package dev.tacmap.xaerotacmap.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config for Xaero Tactical Map.
 *
 * <p>Stored in config/xaerotacmap-client.toml. All values are safe to change
 * at runtime; the built-in config screen writes them directly.</p>
 */
public final class TacMapConfig {

    /** When the in-game waypoint HUD is visible. */
    public enum DisplayMode {
        /** Always visible while in game. */
        ALWAYS,
        /** Only visible while the Xaero world map screen is open. */
        MAP_OPEN,
        /** Hidden until the toggle key is pressed. */
        HOTKEY
    }

    /** Screen corner the HUD panel is anchored to. */
    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public static final ForgeConfigSpec SPEC;

    // ---------------------------------------------------------------- hud
    public static final ForgeConfigSpec.BooleanValue HUD_ENABLED;
    public static final ForgeConfigSpec.EnumValue<DisplayMode> HUD_MODE;
    public static final ForgeConfigSpec.IntValue UPDATE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue DECIMALS;
    public static final ForgeConfigSpec.IntValue MAX_ENTRIES;
    public static final ForgeConfigSpec.DoubleValue HUD_SCALE;
    public static final ForgeConfigSpec.EnumValue<Corner> HUD_CORNER;
    public static final ForgeConfigSpec.IntValue HUD_OFFSET_X;
    public static final ForgeConfigSpec.IntValue HUD_OFFSET_Y;
    public static final ForgeConfigSpec.BooleanValue SHOW_COLOR_DOTS;
    public static final ForgeConfigSpec.BooleanValue SHOW_COORDS;
    public static final ForgeConfigSpec.BooleanValue INCLUDE_DISABLED;
    public static final ForgeConfigSpec.BooleanValue INCLUDE_TEMPORARY;
    public static final ForgeConfigSpec.BooleanValue INCLUDE_DEATHPOINTS;

    // ---------------------------------------------------------------- map
    public static final ForgeConfigSpec.BooleanValue MAP_HOVER_PANEL;
    public static final ForgeConfigSpec.BooleanValue MAP_TACTICAL_LINE;
    public static final ForgeConfigSpec.BooleanValue MAP_LINE_MID_LABEL;
    public static final ForgeConfigSpec.BooleanValue MAP_CHIP_READOUT;
    public static final ForgeConfigSpec.BooleanValue MAP_DEBUG_BAR;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("hud");
        HUD_ENABLED = b.comment("Master switch for the in-game waypoint HUD list.")
                .define("enabled", true);
        HUD_MODE = b.comment("ALWAYS = always visible, MAP_OPEN = only while the Xaero world map is open, HOTKEY = toggle with the bound key (default K).")
                .defineEnum("displayMode", DisplayMode.ALWAYS);
        UPDATE_INTERVAL_TICKS = b.comment("Snapshot refresh interval in ticks. 2 ticks = 10 refreshes per second.")
                .defineInRange("updateIntervalTicks", 2, 1, 40);
        DECIMALS = b.comment("Decimal places for distance/bearing numbers. Default 1 (0.1 precision).")
                .defineInRange("decimals", 1, 0, 2);
        MAX_ENTRIES = b.comment("Maximum number of waypoints shown in the HUD list (nearest first).")
                .defineInRange("maxEntries", 3, 1, 15);
        HUD_SCALE = b.comment("Text scale of the HUD panel. 1.0 = vanilla font size.")
                .defineInRange("scale", 1.0D, 0.5D, 3.0D);
        HUD_CORNER = b.comment("Which screen corner the HUD is anchored to.")
                .defineEnum("corner", Corner.TOP_LEFT);
        HUD_OFFSET_X = b.comment("Horizontal offset (px) from the chosen corner.")
                .defineInRange("offsetX", 6, -500, 4000);
        HUD_OFFSET_Y = b.comment("Vertical offset (px) from the chosen corner. Increase if it overlaps the Xaero minimap.")
                .defineInRange("offsetY", 170, -500, 4000);
        SHOW_COLOR_DOTS = b.comment("Show a colored dot using the waypoint color.")
                .define("colorDots", true);
        SHOW_COORDS = b.comment("Append the waypoint coordinates to each HUD row.")
                .define("showCoordinates", false);
        INCLUDE_DISABLED = b.comment("Include waypoints that are disabled (unchecked) in Xaero.")
                .define("includeDisabledWaypoints", false);
        INCLUDE_TEMPORARY = b.comment("Include temporary waypoints (e.g. one-off navigation destinations).")
                .define("includeTemporaryWaypoints", false);
        INCLUDE_DEATHPOINTS = b.comment("Include death waypoints.")
                .define("includeDeathpoints", false);
        b.pop();

        b.push("map");
        MAP_HOVER_PANEL = b.comment("While the Xaero world map (M) is open, hovering a waypoint shows a tactical panel with distance & bearing.")
                .define("hoverPanel", true);
        MAP_TACTICAL_LINE = b.comment("Draw a dashed tactical line from you to the hovered waypoint on the world map.")
                .define("tacticalLine", true);
        MAP_LINE_MID_LABEL = b.comment("Show distance/bearing label at the middle of the tactical line.")
                .define("lineMidLabel", true);
        MAP_CHIP_READOUT = b.comment("Show the dual bearing readout (yaw + compass + distance) under annotation name chips on the world map. "
                        + "OFF (default) = chips show the marker name only; ON = v4.0.7 style second line with live readouts.")
                .define("chipReadout", false);
        MAP_DEBUG_BAR = b.comment("Show the live diagnostic bar on the world map (build tag, live camera values, hover calibration check). "
                        + "OFF by default for clean screenshots. Turn it ON (config screen or this TOML) while reporting issues - "
                        + "it proves whether the overlay reads live map state.")
                .define("debugBar", false);
        b.pop();

        SPEC = b.build();
    }

    private TacMapConfig() {
    }

    public static void init() {
        // Spec is built statically; this method only forces class initialization
        // at a well-defined time (mod construction).
    }
}
