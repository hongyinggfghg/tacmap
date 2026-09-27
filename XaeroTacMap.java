package dev.tacmap.xaerotacmap;

import com.mojang.logging.LogUtils;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import dev.tacmap.xaerotacmap.net.TacNet;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Xaero Tactical Map - an ATAK-style tactical waypoint awareness layer
 * built on top of Xaero's World Map / Xaero's Minimap.
 *
 * <p>If either Xaero mod is missing, the corresponding features are simply
 * disabled (graceful degradation, no crash). Squad sync is session-only and
 * requires the mod on every client; servers without it are still joinable.</p>
 */
@Mod(XaeroTacMap.MOD_ID)
public final class XaeroTacMap {

    public static final String MOD_ID = "xaerotacmap";
    public static final Logger LOGGER = LogUtils.getLogger();

    public XaeroTacMap() {
        TacMapConfig.init();
        TacNet.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, TacMapConfig.SPEC, "xaerotacmap-client.toml");
        LOGGER.info("[TacMap] Xaero Tactical Map loaded. Detecting Xaero mods lazily at runtime.");
    }
}
