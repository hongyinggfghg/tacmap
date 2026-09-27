package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.config.TacMapConfig;
import dev.tacmap.xaerotacmap.config.TacMapConfig.Corner;
import dev.tacmap.xaerotacmap.config.TacMapConfig.DisplayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * The in-game tactical HUD: the nearest waypoints sorted by distance,
 * refreshed at a fixed cadence (default 10 Hz).
 *
 * <p>Row layout: [color dot] name ... 1234.5m  45.3° NE  Y-135.4°
 * (v4.0.7: the compass bearing and the Minecraft yaw bearing are shown
 * side by side - the yaw value is what players dial in from the F3 readout.)</p>
 */
public final class TacHud implements IGuiOverlay {

    public static final TacHud INSTANCE = new TacHud();

    private static final int BG = 0xA80D1114;
    private static final int BORDER = 0xFF2FA8B8;
    private static final int HEADER = 0xFF7ADBDF;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int TEXT_DIM = 0xFF9FB4B8;
    /** Amber used for the yaw (F3 facing) readout so it stands apart from the compass bearing. */
    private static final int YAW = 0xFFE0B050;
    private static final int ROW_HEIGHT = 11;

    private TacHud() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics gg, float partialTick, int screenWidth, int screenHeight) {
        try {
            renderInner(gg, screenWidth, screenHeight);
        } catch (Throwable t) {
            if (!loggedConfigError) {
                loggedConfigError = true;
                dev.tacmap.xaerotacmap.XaeroTacMap.LOGGER.warn("[TacMap] HUD render skipped (config not ready yet?)", t);
            }
        }
    }

    private static boolean loggedConfigError = false;

    private void renderInner(GuiGraphics gg, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!TacMapConfig.HUD_ENABLED.get()) {
            return;
        }

        DisplayMode mode = TacMapConfig.HUD_MODE.get();
        boolean mapOpen = mc.screen != null
                && XaeroBridge.worldmapPresent()
                && GuiMapHooks.isMapScreen(mc.screen);
        boolean hotkeyActive = WaypointCache.isHotkeyShown();
        boolean visible = switch (mode) {
            case HOTKEY -> hotkeyActive;
            case ALWAYS -> !hotkeyActive;
            case MAP_OPEN -> mapOpen && !hotkeyActive;
        };
        if (!visible) {
            return;
        }

        List<WaypointEntry> all = WaypointCache.snapshot();
        if (all.isEmpty()) {
            renderStatus(gg, screenWidth, screenHeight);
            return;
        }
        int count = Math.min(TacMapConfig.MAX_ENTRIES.get(), all.size());

        Font font = mc.font;
        int decimals = TacMapConfig.DECIMALS.get();
        boolean dots = TacMapConfig.SHOW_COLOR_DOTS.get();
        boolean coords = TacMapConfig.SHOW_COORDS.get();

        // ---- measure -------------------------------------------------
        String title = Component.translatable("xaerotacmap.hud.title").getString();
        String titleFull = title + " " + count;
        int rightW = 0;
        String[] dists = new String[count];
        String[] brgs = new String[count];
        String[] yaws = new String[count];
        String yawTag = Component.translatable("xaerotacmap.brg.yaw_s").getString();
        for (int i = 0; i < count; i++) {
            WaypointEntry e = all.get(i);
            dists[i] = BearingMath.fmt(e.distance, decimals) + "m";
            brgs[i] = BearingMath.fmt(e.bearing, decimals) + "\u00B0 "
                    + Component.translatable(BearingMath.dirKey(e.bearing)).getString();
            yaws[i] = yawTag + BearingMath.fmt(e.yaw, decimals) + "\u00B0";
            rightW = Math.max(rightW, font.width(dists[i] + "  " + brgs[i] + "  " + yaws[i]));
        }
        int nameW = 0;
        for (int i = 0; i < count; i++) {
            nameW = Math.max(nameW, font.width(trim(font, all.get(i).name, 150)));
        }
        int contentW = (dots ? 6 : 0) + nameW + 8 + rightW;
        int boxW = Math.max(font.width(titleFull), contentW) + 8;
        int rowStep = ROW_HEIGHT + (coords ? 9 : 0);
        int boxH = 13 + count * rowStep + 3;

        // ---- anchor --------------------------------------------------
        Corner corner = TacMapConfig.HUD_CORNER.get();
        float scale = TacMapConfig.HUD_SCALE.get().floatValue();
        int offX = TacMapConfig.HUD_OFFSET_X.get();
        int offY = TacMapConfig.HUD_OFFSET_Y.get();
        int anchorX = switch (corner) {
            case TOP_LEFT, BOTTOM_LEFT -> offX;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - Math.round(boxW * scale) - offX;
        };
        int anchorY = switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> offY;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - Math.round(boxH * scale) - offY;
        };
        if (boxW > screenWidth || boxH > screenHeight) {
            return;
        }

        // ---- draw (scaled around the anchor) --------------------------
        var pose = gg.pose();
        pose.pushPose();
        pose.translate(anchorX, anchorY, 0);
        if (scale != 1.0F) {
            pose.scale(scale, scale, 1.0F);
        }

        gg.fill(0, 0, boxW, boxH, BG);
        // border
        gg.fill(0, 0, boxW, 1, BORDER);
        gg.fill(0, boxH - 1, boxW, boxH, BORDER);
        gg.fill(0, 0, 1, boxH, BORDER);
        gg.fill(boxW - 1, 0, boxW, boxH, BORDER);
        // left accent bar
        gg.fill(0, 0, 2, boxH, BORDER);

        gg.drawString(font, titleFull, 6, 4, HEADER, true);

        int y = 13;
        for (int i = 0; i < count; i++) {
            WaypointEntry e = all.get(i);
            int x = 6;
            if (dots) {
                gg.fill(x, y + 3, x + 3, y + 6, BearingMath.opaque(e.colorRgb));
                x += 6;
            }
            String name = trim(font, e.name, 150);
            gg.drawString(font, name, x, y, TEXT, true);
            x += nameW + 8;

            gg.drawString(font, dists[i], x, y, TEXT, true);
            // v4.0.7: compass bearing and yaw side by side, both right-aligned
            int yawX = x + rightW - font.width(yaws[i]);
            int brgX = yawX - 6 - font.width(brgs[i]);
            gg.drawString(font, brgs[i], brgX, y, TEXT_DIM, true);
            gg.drawString(font, yaws[i], yawX, y, YAW, true);

            if (coords) {
                String pos = e.x + ", " + e.y + ", " + e.z;
                gg.drawString(font, pos, 6, y + ROW_HEIGHT - 1, TEXT_DIM, true);
            }
            y += rowStep;
        }

        pose.popPose();
    }

    /** Dim one-row panel explaining WHY the list is empty (diagnostics). */
    private void renderStatus(GuiGraphics gg, int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        String state = WaypointCache.status();
        String text = Component.translatable("xaerotacmap.hud.title").getString()
                + ": " + Component.translatable("xaerotacmap.state." + state).getString();
        int boxW = font.width(text) + 10;
        int boxH = 15;
        Corner corner = TacMapConfig.HUD_CORNER.get();
        int offX = TacMapConfig.HUD_OFFSET_X.get();
        int offY = TacMapConfig.HUD_OFFSET_Y.get();
        int anchorX = switch (corner) {
            case TOP_LEFT, BOTTOM_LEFT -> offX;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - boxW - offX;
        };
        int anchorY = switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> offY;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - boxH - offY;
        };
        if (boxW > screenWidth || boxH > screenHeight) {
            return;
        }
        gg.fill(anchorX, anchorY, anchorX + boxW, anchorY + boxH, BG);
        gg.fill(anchorX, anchorY, anchorX + boxW, anchorY + 1, BORDER);
        gg.fill(anchorX, anchorY + boxH - 1, anchorX + boxW, anchorY + boxH, BORDER);
        gg.fill(anchorX, anchorY, anchorX + 2, anchorY + boxH, BORDER);
        gg.drawString(font, text, anchorX + 5, anchorY + 4, TEXT_DIM, true);
    }

    /** Truncates the string with an ellipsis so it fits the given pixel width. */
    public static String trim(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 1 && font.width(sb + "\u2026") > maxWidth) {
            sb.setLength(sb.length() - 1);
        }
        return sb + "\u2026";
    }
}
