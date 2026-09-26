package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import dev.tacmap.xaerotacmap.config.TacMapConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Mod-bus client setup: keybinds, HUD overlay registration and the
 * config screen extension point (used by the Forge mods list screen).
 */
@Mod.EventBusSubscriber(modid = XaeroTacMap.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TacMapClient {

    private TacMapClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(KeyBinds.TOGGLE_HUD);
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("tac_hud", TacHud.INSTANCE);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> FMLJavaModLoadingContext.get().getContainer()
                .registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                                (minecraft, parent) -> new TacMapConfigScreen(parent))));
        XaeroTacMap.LOGGER.info("[TacMap] Client setup done. HUD overlay + config screen registered.");
    }

    /** Touches the config class early on the client so values are ready. */
    static void ensureConfig() {
        TacMapConfig.init();
        IGuiOverlay unused = TacHud.INSTANCE; // keeps the class graph warm
    }
}
