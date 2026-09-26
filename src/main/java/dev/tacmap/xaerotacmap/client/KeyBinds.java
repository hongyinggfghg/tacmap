package dev.tacmap.xaerotacmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Keybinds for Xaero Tactical Map.
 * Default: K toggles the HUD (relevant in HOTKEY mode; works in any mode as an override).
 */
public final class KeyBinds {

    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
            "key.xaerotacmap.toggle_hud",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.xaerotacmap");

    private KeyBinds() {
    }
}
