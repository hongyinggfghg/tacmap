package dev.tacmap.xaerotacmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Keybinds for Xaero Tactical Map.
 *
 * <ul>
 *   <li>K - toggles the HUD (relevant in HOTKEY mode; works in any mode).</li>
 *   <li>J - opens the squad panel while in game (the same key also works
 *       while the world map is open, handled on the screen event bus).</li>
 * </ul>
 */
public final class KeyBinds {

    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
            "key.xaerotacmap.toggle_hud",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.xaerotacmap");

    public static final KeyMapping OPEN_SQUAD = new KeyMapping(
            "key.xaerotacmap.open_squad",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.xaerotacmap");

    private KeyBinds() {
    }
}
