package dev.tacmap.xaerotacmap;

import com.mojang.logging.LogUtils;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Xaero Tactical Map - an ATAK-style tactical waypoint awareness layer
 * built on top of Xaero's World Map / Xaero's Minimap.
 *
 * <p>Client-side only. If either Xaero mod is missing, the corresponding
 * features are simply disabled (graceful degradation, no crash).</p>
 */
@Mod(XaeroTacMap.MOD_ID)
public final class XaeroTacMap {

    public static final String MOD_ID = "xaerotacmap";
    public static final Logger LOGGER = LogUtils.getLogger();

    public XaeroTacMap() {
        TacMapConfig.init();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, TacMapConfig.SPEC, "xaerotacmap-client.toml");
        LOGGER.info("[TacMap] Xaero Tactical Map loaded. Detecting Xaero mods lazily at runtime.");
    }
}
