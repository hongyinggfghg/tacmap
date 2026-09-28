package dev.tacmap.xaerotacmap.client;

import dev.tacmap.xaerotacmap.config.TacMapConfig;
import dev.tacmap.xaerotacmap.config.TacMapConfig.Corner;
import dev.tacmap.xaerotacmap.config.TacMapConfig.DisplayMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Built-in config screen. Registered through Forge's ConfigScreenHandler
 * extension point, so the "Config" button in the Mods list opens it.
 * All changes apply immediately and are saved to xaerotacmap-client.toml.
 */
public final class TacMapConfigScreen extends Screen {

    private static final int COL_W = 152;
    private static final int ROW_H = 24;
    private static final int START_Y = 42;
    private static final int ROWS = 7;

    private static final List<Integer> INTERVALS = List.of(1, 2, 3, 5, 10, 20);
    private static final List<Integer> ENTRIES = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    private static final List<Integer> DECIMALS = List.of(0, 1, 2);
    private static final List<Double> SCALES = List.of(0.5D, 0.75D, 1.0D, 1.25D, 1.5D, 2.0D);

    private final Screen parent;
    private EditBox offsetXBox;
    private EditBox offsetYBox;

    public TacMapConfigScreen(Screen parent) {
        super(Component.translatable("xaerotacmap.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int col1X = this.width / 2 - 158;
        int col2X = this.width / 2 + 6;

        // ---------------- column 1 ----------------
        int r = 0;
        addWidget(boolCycle(col1X, r, TacMapConfig.HUD_ENABLED, "xaerotacmap.config.hud_enabled"));
        r++;
        addWidget(CycleButton.<DisplayMode>builder(TacMapConfigScreen::modeLabel)
                .withValues(DisplayMode.values())
                .withInitialValue(TacMapConfig.HUD_MODE.get())
                .create(col1X, yAt(r), COL_W, 20,
                        Component.translatable("xaerotacmap.config.hud_mode"),
                        (btn, val) -> TacMapConfig.HUD_MODE.set(val)));
        r++;
        addWidget(CycleButton.<Integer>builder(v -> Component.translatable("xaerotacmap.config.update_interval_value", v))
                .withValues(INTERVALS)
                .withInitialValue(nearestIn(INTERVALS, TacMapConfig.UPDATE_INTERVAL_TICKS.get()))
                .create(col1X, yAt(r), COL_W, 20,
                        Component.translatable("xaerotacmap.config.update_interval"),
                        (btn, val) -> TacMapConfig.UPDATE_INTERVAL_TICKS.set(val)));
        r++;
        addWidget(CycleButton.<Integer>builder(v -> Component.literal(String.valueOf(v)))
                .withValues(DECIMALS)
                .withInitialValue(nearestIn(DECIMALS, TacMapConfig.DECIMALS.get()))
                .create(col1X, yAt(r), COL_W, 20,
                        Component.translatable("xaerotacmap.config.decimals"),
                        (btn, val) -> TacMapConfig.DECIMALS.set(val)));
        r++;
        addWidget(CycleButton.<Integer>builder(v -> Component.literal(String.valueOf(v)))
                .withValues(ENTRIES)
                .withInitialValue(nearestIn(ENTRIES, TacMapConfig.MAX_ENTRIES.get()))
                .create(col1X, yAt(r), COL_W, 20,
                        Component.translatable("xaerotacmap.config.max_entries"),
                        (btn, val) -> TacMapConfig.MAX_ENTRIES.set(val)));
        r++;
        addWidget(CycleButton.<Double>builder(v -> Component.literal(BearingMath.fmt(v, 2)))
                .withValues(SCALES)
                .withInitialValue(nearestIn(SCALES, TacMapConfig.HUD_SCALE.get()))
                .create(col1X, yAt(r), COL_W, 20,
                        Component.translatable("xaerotacmap.config.hud_scale"),
                        (btn, val) -> TacMapConfig.HUD_SCALE.set(val)));
        r++;
        addWidget(CycleButton.<Corner>builder(TacMapConfigScreen::cornerLabel)
                .withValues(Corner.values())
                .withInitialValue(TacMapConfig.HUD_CORNER.get())
                .create(col1X, yAt(r), COL_W, 20,
                        Component.translatable("xaerotacmap.config.corner"),
                        (btn, val) -> TacMapConfig.HUD_CORNER.set(val)));

        // ---------------- column 2 ----------------
        col2X = this.width / 2 + 6;
        int labelW = 70;

        Component lblX = Component.translatable("xaerotacmap.config.offset_x");
        this.offsetXBox = new EditBox(this.font, col2X + labelW, yAt(0) + 2, COL_W - labelW, 16, lblX);
        this.offsetXBox.setValue(String.valueOf(TacMapConfig.HUD_OFFSET_X.get()));
        this.offsetXBox.setFilter(s -> s.matches("-?[0-9]{0,5}"));
        addWidget(this.offsetXBox);

        Component lblY = Component.translatable("xaerotacmap.config.offset_y");
        this.offsetYBox = new EditBox(this.font, col2X + labelW, yAt(1) + 2, COL_W - labelW, 16, lblY);
        this.offsetYBox.setValue(String.valueOf(TacMapConfig.HUD_OFFSET_Y.get()));
        this.offsetYBox.setFilter(s -> s.matches("-?[0-9]{0,5}"));
        addWidget(this.offsetYBox);

        r = 2;
        addWidget(boolCycle(col2X, r, TacMapConfig.SHOW_COLOR_DOTS, "xaerotacmap.config.color_dots"));
        r++;
        addWidget(boolCycle(col2X, r, TacMapConfig.SHOW_COORDS, "xaerotacmap.config.show_coords"));
        r++;
        addWidget(boolCycle(col2X, r, TacMapConfig.INCLUDE_DISABLED, "xaerotacmap.config.include_disabled"));
        r++;
        addWidget(boolCycle(col2X, r, TacMapConfig.INCLUDE_TEMPORARY, "xaerotacmap.config.include_temporary"));
        r++;
        addWidget(boolCycle(col2X, r, TacMapConfig.INCLUDE_DEATHPOINTS, "xaerotacmap.config.include_death"));

        // ---------------- bottom row: quick feature toggles ----------------
        // v4.0.9: 4 -> 5 toggles (chip readout); v4.0.12: 5 -> 6 (annotations
        // in the HUD list). 76 px buttons so all six fit even on 480-wide
        // GUIs (GUI scale 4 on 1080p).
        int mapY = yAt(ROWS) + 8;
        int btnW = 76;
        int x0 = this.width / 2 - (btnW * 6 + 15) / 2;
        addWidget(boolCycle(x0, mapY, btnW, TacMapConfig.MAP_HOVER_PANEL, "xaerotacmap.config.hover_panel"));
        addWidget(boolCycle(x0 + (btnW + 3), mapY, btnW, TacMapConfig.MAP_TACTICAL_LINE, "xaerotacmap.config.tactical_line"));
        addWidget(boolCycle(x0 + (btnW + 3) * 2, mapY, btnW, TacMapConfig.MAP_LINE_MID_LABEL, "xaerotacmap.config.line_label"));
        addWidget(boolCycle(x0 + (btnW + 3) * 3, mapY, btnW, TacMapConfig.MAP_CHIP_READOUT, "xaerotacmap.config.chip_readout"));
        addWidget(boolCycle(x0 + (btnW + 3) * 4, mapY, btnW, TacMapConfig.MAP_DEBUG_BAR, "xaerotacmap.config.debug_bar"));
        addWidget(boolCycle(x0 + (btnW + 3) * 5, mapY, btnW, TacMapConfig.HUD_ANNOTATIONS, "xaerotacmap.config.hud_annotations"));

        this.addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.config.done"), b -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20)
                .build());
    }

    private static CycleButton<Boolean> boolCycle(int x, int row, ForgeConfigSpec.BooleanValue value, String key) {
        return boolCycle(x, yAt(row), COL_W, value, key);
    }

    private static CycleButton<Boolean> boolCycle(int x, int y, int w, ForgeConfigSpec.BooleanValue value, String key) {
        return CycleButton.onOffBuilder(value.get())
                .create(x, y, w, 20, Component.translatable(key),
                        (btn, val) -> value.set(val));
    }

    private static int yAt(int row) {
        return START_Y + row * ROW_H;
    }

    private void addWidget(AbstractWidget widget) {
        this.addRenderableWidget(widget);
    }

    private static <T> T nearestIn(List<T> list, T current) {
        if (list.contains(current)) {
            return current;
        }
        return list.get(list.size() - 1);
    }

    private static Component modeLabel(DisplayMode mode) {
        return Component.translatable(switch (mode) {
            case ALWAYS -> "xaerotacmap.config.mode_always";
            case MAP_OPEN -> "xaerotacmap.config.mode_map_open";
            case HOTKEY -> "xaerotacmap.config.mode_hotkey";
        });
    }

    private static Component cornerLabel(Corner corner) {
        return Component.translatable(switch (corner) {
            case TOP_LEFT -> "xaerotacmap.config.corner_tl";
            case TOP_RIGHT -> "xaerotacmap.config.corner_tr";
            case BOTTOM_LEFT -> "xaerotacmap.config.corner_bl";
            case BOTTOM_RIGHT -> "xaerotacmap.config.corner_br";
        });
    }

    @Override
    public void onClose() {
        applyInt(this.offsetXBox, TacMapConfig.HUD_OFFSET_X);
        applyInt(this.offsetYBox, TacMapConfig.HUD_OFFSET_Y);
        this.minecraft.setScreen(this.parent);
    }

    private static void applyInt(EditBox box, ForgeConfigSpec.IntValue target) {
        if (box == null) {
            return;
        }
        try {
            int v = Integer.parseInt(box.getValue().trim());
            target.set(Mth.clamp(v, -500, 4000));
        } catch (NumberFormatException ignored) {
            // Keep the previous value when the text is not a valid number.
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFF7ADBDF);

        int col2X = this.width / 2 + 6;
        guiGraphics.drawString(this.font, Component.translatable("xaerotacmap.config.offset_x"),
                col2X, yAt(0) + 7, 0xFFE8ECEE, false);
        guiGraphics.drawString(this.font, Component.translatable("xaerotacmap.config.offset_y"),
                col2X, yAt(1) + 7, 0xFFE8ECEE, false);
    }
}
