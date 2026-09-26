package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import net.minecraftforge.fml.ModList;

/**
 * Runtime detection of the Xaero mods. All Xaero-typed classes are isolated in
 * {@link XaeroWaypointSource} and {@link GuiMapHooks}; those classes are only
 * loaded after the corresponding check here returns true, so the game never
 * crashes when a Xaero mod is missing.
 */
public final class XaeroBridge {

    private static Boolean minimapPresent;
    private static Boolean worldmapPresent;

    private XaeroBridge() {
    }

    /** True when Xaero's Minimap (waypoint data owner) is installed. */
    public static boolean minimapPresent() {
        if (minimapPresent == null) {
            try {
                minimapPresent = ModList.get().isLoaded("xaerominimap");
            } catch (Throwable t) {
                XaeroTacMap.LOGGER.warn("[TacMap] Failed to query mod list", t);
                minimapPresent = Boolean.FALSE;
            }
        }
        return minimapPresent;
    }

    /** True when Xaero's World Map is installed. */
    public static boolean worldmapPresent() {
        if (worldmapPresent == null) {
            try {
                worldmapPresent = ModList.get().isLoaded("xaeroworldmap");
            } catch (Throwable t) {
                XaeroTacMap.LOGGER.warn("[TacMap] Failed to query mod list", t);
                worldmapPresent = Boolean.FALSE;
            }
        }
        return worldmapPresent;
    }
}
