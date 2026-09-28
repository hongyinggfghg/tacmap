package dev.tacmap.xaerotacmap.client.annotate;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.tacmap.xaerotacmap.annotation.TacAnnotation.Symbol;
import dev.tacmap.xaerotacmap.client.annotate.DrawingController.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Annotation toolbar rendered on the LEFT edge of the Xaero world map, plus
 * the expandable symbol palette and the inline label input box. Hit testing
 * mirrors the drawn layout exactly; clicks on toolbar/palette are consumed by
 * the caller (canceled so Xaero never sees them).
 *
 * <p>v4.0.14: the toolbar wraps into balanced columns adaptively - the single
 * 11-button column (231 px) ran off the bottom of the map screen at GUI
 * scale 4, hiding the enemy-route button. See {@code maxPerCol()}.</p>
 */
public final class AnnotationToolbar {

    /** Toolbar action ids (returned by {@link #hit}). */
    public static final int ACT_NONE_TOOL = 0;
    public static final int ACT_POINT = 1;
    public static final int ACT_ROUTE = 2;
    public static final int ACT_POLYGON = 3;
    public static final int ACT_CIRCLE = 4;
    public static final int ACT_ERASE = 5;
    public static final int ACT_PALETTE = 6;
    public static final int ACT_SQUAD = 7;
    public static final int ACT_EXPORT = 8;
    public static final int ACT_IMPORT = 9;
    /** v4.0.10 - APPENDED so every existing action id keeps its meaning. */
    public static final int ACT_ENEMY_ROUTE = 10;

    private static final int BTN = 18;
    private static final int GAP = 3;
    private static final int X = 8;
    private static final int Y0 = 64;
    /** Extra horizontal room between wrapped toolbar columns. */
    private static final int COL_GAP = 8;
    /** v4.0.10: 10 -> 11 buttons (enemy route arrows appended at the end). */
    private static final int COUNT = 11;

    private static final int PANEL_BG = 0xC60D1114;
    private static final int PANEL_BORDER = 0x662FA8B8;
    private static final int SEL = 0xFF2FA8B8;
    private static final int ICON = 0xFFDDEAEE;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int TEXT_DIM = 0xFF9FB4B8;
    private static final int WARN = 0xFFFFD54F;
    /** Friendly palette section theme (blue). */
    private static final int ALLY_ACCENT = 0xFF3D7DD8;
    /** Enemy palette section theme (red). */
    private static final int ENEMY_ACCENT = 0xFFE04545;

    private AnnotationToolbar() {
    }

    // ------------------------------------------------------------ layout
    //
    // v4.0.14: adaptive column wrap. The single 11-button column needed
    // 231 px and ran off the bottom of the map screen at GUI scale 4 (270
    // GUI px tall on 1080p) - the enemy-route button was drawn off-screen.
    // The layout now wraps into balanced columns whenever one column would
    // not fit; at typical heights it stays the classic single column.
    // Order is COLUMN-MAJOR so buttons 0..5 (the drawing tools) keep their
    // familiar positions; palette/squad/export/import/enemy route move to
    // the second column only when wrapping kicks in.

    private static int guiH() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    /** Rows that fit in one column under the current GUI height (>= 1). */
    private static int maxPerCol() {
        int usable = guiH() - Y0 - 6;
        return Math.max(1, (usable + GAP) / (BTN + GAP));
    }

    private static int cols() {
        return (COUNT + maxPerCol() - 1) / maxPerCol();
    }

    /** Balanced rows per column (every column gets this count or one less). */
    private static int perCol() {
        return (COUNT + cols() - 1) / cols();
    }

    private static int colOf(int i) {
        return i / perCol();
    }

    private static int rowOf(int i) {
        return i % perCol();
    }

    private static int btnX(int i) {
        return X + colOf(i) * (BTN + COL_GAP);
    }

    private static int btnY(int i) {
        return Y0 + rowOf(i) * (BTN + GAP);
    }

    /** Total drawn width of the toolbar (all columns). */
    private static int toolbarW() {
        return (cols() - 1) * (BTN + COL_GAP) + BTN;
    }

    private static int paletteX() {
        return X + toolbarW() + 6;
    }

    private static int paletteW() {
        return 5 * (BTN + GAP) + 4;
    }

    /** Y of the first friendly symbol row (below the section title). */
    private static int allyGridY() {
        return 12;
    }

    private static int allyRows() {
        return (AnnotationSymbolsRenderer.ALLY.length + 4) / 5;
    }

    /** Y of the enemy section title (below the divider). */
    private static int enemyTitleY() {
        return allyGridY() + allyRows() * (BTN + GAP) + 6;
    }

    /** Y of the enemy symbol grid (below the section title). */
    private static int enemyGridY() {
        return enemyTitleY() + 12;
    }

    /** v4.0.6: enemy pack grew to 8 symbols - two rows of 5. */
    private static int enemyRows() {
        return (AnnotationSymbolsRenderer.ENEMY.length + 4) / 5;
    }

    private static int paletteH() {
        return enemyGridY() + enemyRows() * (BTN + GAP) + 4;
    }

    private static int paletteY() {
        return Y0;
    }

    public static int totalHeight() {
        return perCol() * (BTN + GAP);
    }

    // ------------------------------------------------------------ hit testing

    /** Toolbar button index under the cursor, or -1. */
    public static int hit(double mx, double my) {
        if (my < Y0 - GAP) {
            return -1;
        }
        for (int i = 0; i < COUNT; i++) {
            int x = btnX(i);
            int y = btnY(i);
            if (mx >= x - 2 && mx <= x + BTN + 2 && my >= y && my <= y + BTN) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Symbol under the cursor in the two-section palette (friendly on top,
     * enemy below), or null.
     */
    public static Symbol hitPalette(double mx, double my) {
        if (!DrawingController.paletteOpen()) {
            return null;
        }
        double px = mx - paletteX();
        double py = my - paletteY();
        if (px < 2 || px > paletteW() - 2) {
            return null;
        }
        // friendly grid
        int aRows = allyRows();
        int aY = allyGridY();
        if (py >= aY && py < aY + aRows * (BTN + GAP)) {
            int col = (int) ((px - 2) / (BTN + GAP));
            int row = (int) ((py - aY) / (BTN + GAP));
            if (col >= 0 && col <= 4 && row >= 0) {
                int idx = row * 5 + col;
                if (idx < AnnotationSymbolsRenderer.ALLY.length) {
                    return AnnotationSymbolsRenderer.ALLY[idx];
                }
            }
            return null;
        }
        // enemy grid (two rows since v4.0.6)
        int eY = enemyGridY();
        if (py >= eY && py < eY + enemyRows() * (BTN + GAP)) {
            int col = (int) ((px - 2) / (BTN + GAP));
            int row = (int) ((py - eY) / (BTN + GAP));
            if (col >= 0 && col <= 4 && row >= 0) {
                int idx = row * 5 + col;
                if (idx < AnnotationSymbolsRenderer.ENEMY.length) {
                    return AnnotationSymbolsRenderer.ENEMY[idx];
                }
            }
        }
        return null;
    }

    /** Maps a toolbar button index to its action id. */
    public static int actionOf(int index) {
        return index; // layout order == action ids
    }

    // ------------------------------------------------------------ drawing

    public static void draw(GuiGraphics gg, Font font, double mouseX, double mouseY) {
        // toolbar + palette draw AFTER the marker overlay frame, so establish
        // our own GL state here (ShapeDraw.open() also self-protects, this
        // covers the widget fills/stamps in between)
        ShapeDraw.beginFrame();
        try {
            drawInner(gg, font, mouseX, mouseY);
        } finally {
            ShapeDraw.endFrame();
        }
    }

    private static void drawInner(GuiGraphics gg, Font font, double mouseX, double mouseY) {
        int color = ClientMarkerStore.squadColor();

        // squad chip above the toolbar
        String squadLabel = ClientMarkerStore.inSquad()
                ? Component.translatable("xaerotacmap.toolbar.squad_tag",
                        ClientMarkerStore.squad().name).getString()
                : Component.translatable("xaerotacmap.toolbar.no_squad").getString();
        int chipW = font.width(squadLabel) + 16;
        gg.fill(X - 2, Y0 - 16, X + Math.max(chipW, BTN + 4), Y0 - 4, PANEL_BG);
        gg.fill(X - 2, Y0 - 16, X + Math.max(chipW, BTN + 4), Y0 - 15, PANEL_BORDER);
        gg.fill(X, Y0 - 13, X + 8, Y0 - 5, color);
        gg.drawString(font, squadLabel, X + 11, Y0 - 13, TEXT, true);

        for (int i = 0; i < COUNT; i++) {
            int x = btnX(i);
            int y = btnY(i);
            boolean hover = hit(mouseX, mouseY) == i;
            boolean selected = false;
            Tool t = DrawingController.tool();
            switch (i) {
                case ACT_NONE_TOOL -> selected = t == Tool.NONE;
                case ACT_POINT -> selected = t == Tool.POINT;
                case ACT_ROUTE -> selected = t == Tool.ROUTE;
                case ACT_POLYGON -> selected = t == Tool.POLYGON;
                case ACT_CIRCLE -> selected = t == Tool.CIRCLE;
                case ACT_ERASE -> selected = t == Tool.ERASE;
                case ACT_ENEMY_ROUTE -> selected = t == Tool.ENEMY_ROUTE;
                case ACT_PALETTE -> selected = DrawingController.paletteOpen();
                default -> selected = false;
            }
            int border = selected ? SEL : (hover ? 0xFF5FC3D3 : PANEL_BORDER);
            gg.fill(x - 2, y - 2, x + BTN + 2, y + BTN + 2, PANEL_BG);
            gg.fill(x - 2, y - 2, x + BTN + 2, y - 1, border);
            gg.fill(x - 2, y + BTN + 1, x + BTN + 2, y + BTN + 2, border);
            gg.fill(x - 2, y - 2, x - 1, y + BTN + 2, border);
            gg.fill(x + BTN + 1, y - 2, x + BTN + 2, y + BTN + 2, border);
            drawButtonIcon(gg, font, i, x, y, ICON);
            if (hover) {
                drawToolbarTooltip(gg, font, mouseX, mouseY, i);
            }
        }

        if (DrawingController.paletteOpen()) {
            drawPalette(gg, font, mouseX, mouseY);
        }

        // hints
        if (DrawingController.tool() != Tool.NONE && !ClientMarkerStore.inSquad()) {
            String warn = Component.translatable("xaerotacmap.annotate.need_squad").getString();
            int w = font.width(warn);
            int hx = X + toolbarW() + 8;
            int hy = btnY(0) + 2;
            gg.fill(hx - 3, hy - 2, hx + w + 3, hy + 10, PANEL_BG);
            gg.drawString(font, warn, hx, hy, WARN, true);
        }

        drawLabelEditor(gg, font);
    }

    private static void drawPalette(GuiGraphics gg, Font font, double mouseX, double mouseY) {
        int px = paletteX();
        int py = paletteY();
        int pw = paletteW();
        int ph = paletteH();
        gg.fill(px, py, px + pw, py + ph, PANEL_BG);
        gg.fill(px, py, px + pw, py + 1, PANEL_BORDER);
        gg.fill(px, py + ph - 1, px + pw, py + ph, PANEL_BORDER);
        gg.fill(px, py, px + 1, py + ph, PANEL_BORDER);
        gg.fill(px + pw - 1, py, px + pw, py + ph, PANEL_BORDER);

        Symbol hover = hitPalette(mouseX, mouseY);

        // ---- friendly section (blue accent, squad-colored symbols)
        gg.fill(px + 1, py + allyGridY() - 2, px + pw - 1, py + allyGridY() - 1, ALLY_ACCENT);
        gg.drawString(font, Component.translatable("xaerotacmap.toolbar.palette_ally").getString(),
                px + 4, py + 2, 0xFF9EC2F5, true);
        drawSymbolGrid(gg, font, AnnotationSymbolsRenderer.ALLY, px, py + allyGridY(),
                hover, false);

        // ---- divider
        int divY = py + enemyTitleY() - 4;
        gg.fill(px + 4, divY, px + pw - 4, divY + 1, PANEL_BORDER);

        // ---- enemy section (red accent, always-red symbols)
        gg.fill(px + 1, py + enemyTitleY() - 2, px + pw - 1, py + enemyTitleY() - 1, ENEMY_ACCENT);
        gg.drawString(font, Component.translatable("xaerotacmap.toolbar.palette_enemy").getString(),
                px + 4, py + enemyTitleY(), 0xFFF5A0A0, true);
        drawSymbolGrid(gg, font, AnnotationSymbolsRenderer.ENEMY, px, py + enemyGridY(),
                hover, true);

        if (hover != null) {
            String name = Component.translatable(hover.langKey()).getString();
            int w = font.width(name);
            gg.fill((int) mouseX + 6, (int) mouseY + 8, (int) mouseX + w + 12, (int) mouseY + 20, PANEL_BG);
            gg.drawString(font, name, (int) mouseX + 9, (int) mouseY + 10, TEXT, true);
        }
    }

    /** Draws one palette grid row-set starting at local (px, gridY). */
    private static void drawSymbolGrid(GuiGraphics gg, Font font, Symbol[] symbols,
                                       int px, int gridY, Symbol hover, boolean enemy) {
        for (int i = 0; i < symbols.length; i++) {
            int row = i / 5;
            int col = i % 5;
            int cx = px + 2 + col * (BTN + GAP) + BTN / 2;
            int cy = gridY + row * (BTN + GAP) + BTN / 2;
            boolean isSel = DrawingController.pointSymbol() == symbols[i]
                    && DrawingController.tool() == Tool.POINT;
            boolean isHover = hover == symbols[i];
            if (isSel || isHover) {
                int tint = enemy ? (isSel ? 0x50E04545 : 0x30FFFFFF)
                        : (isSel ? 0x503D7DD8 : 0x30FFFFFF);
                gg.fill(cx - BTN / 2, cy - BTN / 2,
                        cx + BTN / 2, cy + BTN / 2, tint);
            }
            int color = enemy ? 0xFFFF5252 : ClientMarkerStore.squadColor();
            AnnotationSymbolsRenderer.draw(gg, font, cx, cy, symbols[i], color, 6.0D);
        }
    }

    /** Chinese name tooltip for a hovered toolbar button (v4.0.5 i18n pass). */
    private static void drawToolbarTooltip(GuiGraphics gg, Font font,
                                           double mouseX, double mouseY, int btn) {
        String key = switch (btn) {
            case ACT_NONE_TOOL -> "xaerotacmap.act.none";
            case ACT_POINT -> "xaerotacmap.act.point";
            case ACT_ROUTE -> "xaerotacmap.act.route";
            case ACT_POLYGON -> "xaerotacmap.act.polygon";
            case ACT_CIRCLE -> "xaerotacmap.act.circle";
            case ACT_ERASE -> "xaerotacmap.act.erase";
            case ACT_PALETTE -> "xaerotacmap.act.palette";
            case ACT_SQUAD -> "xaerotacmap.act.squad";
            case ACT_EXPORT -> "xaerotacmap.act.export";
            case ACT_IMPORT -> "xaerotacmap.act.import";
            case ACT_ENEMY_ROUTE -> "xaerotacmap.act.enemy_route";
            default -> null;
        };
        if (key == null) {
            return;
        }
        String text = Component.translatable(key).getString();
        int w = font.width(text);
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int tx = (int) mouseX + 10;
        if (tx + w + 8 > sw) {
            tx = (int) mouseX - w - 10;
        }
        int ty = (int) mouseY - 8;
        gg.fill(tx - 3, ty - 2, tx + w + 3, ty + 10, PANEL_BG);
        gg.fill(tx - 3, ty - 2, tx + w + 3, ty - 1, PANEL_BORDER);
        gg.drawString(font, text, tx, ty, TEXT, true);
    }

    private static void drawLabelEditor(GuiGraphics gg, Font font) {
        if (!DrawingController.isAwaitingLabel()) {
            return;
        }
        String typed = DrawingController.labelBuffer();
        String prompt = Component.translatable("xaerotacmap.annotate.label_prompt").getString();
        String line = typed + "_";
        int w = Math.max(font.width(prompt), font.width(line)) + 12;
        int x = DrawingController.labelAnchor()[0] + 10;
        int y = DrawingController.labelAnchor()[1] + 10;
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        x = Math.max(4, Math.min(sw - w - 4, x));
        y = Math.max(4, Math.min(sh - 34, y));
        gg.fill(x, y, x + w, y + 30, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, SEL);
        gg.fill(x, y + 29, x + w, y + 30, SEL);
        gg.drawString(font, prompt, x + 6, y + 4, TEXT_DIM, true);
        gg.drawString(font, line, x + 6, y + 16, TEXT, true);
        String confirm = Component.translatable("xaerotacmap.annotate.label_confirm").getString();
        gg.drawString(font, confirm, x + 6, y + 34, TEXT_DIM, true);
    }

    // ------------------------------------------------------------ icons

    private static void drawButtonIcon(GuiGraphics gg, Font font, int i, int x, int y, int color) {
        int abgr = ShapeDraw.abgr(color);
        double cx = x + BTN / 2.0D;
        double cy = y + BTN / 2.0D;
        BufferBuilder bb = ShapeDraw.open();
        PoseStack.Pose pose = gg.pose().last();
        switch (i) {
            case ACT_NONE_TOOL -> {
                // pointer arrow
                double[] xs = {cx - 4, cx - 4, cx + 4.5};
                double[] ys = {cy - 5, cy + 5, cy + 0.5};
                ShapeDraw.fillPoly(bb, pose, xs, ys, abgr);
            }
            case ACT_POINT -> {
                ShapeDraw.ring(bb, pose, cx, cy, 4.5D, 0.8D, abgr, 14);
                ShapeDraw.fillCircle(bb, pose, cx, cy, 1.2D, abgr, 8);
            }
            case ACT_ROUTE -> {
                double[] xs = {cx - 6, cx - 2, cx + 2, cx + 6};
                double[] ys = {cy + 4, cy - 1, cy + 2, cy - 4};
                ShapeDraw.strokeDashed(bb, pose, xs, ys, 0.8D, false, 2.5D, 1.8D, abgr);
                ShapeDraw.arrow(bb, pose, cx + 6, cy - 4, 0.55D, -0.83D, 4.0D, 2.4D, abgr);
            }
            case ACT_POLYGON -> {
                double[] xs = {cx, cx + 5.5, cx + 3.5, cx - 3.5, cx - 5.5};
                double[] ys = {cy - 5.5, cy - 1.5, cy + 5, cy + 5, cy - 1.5};
                ShapeDraw.strokePolyline(bb, pose, xs, ys, 0.8D, true, abgr);
                ShapeDraw.fillCircle(bb, pose, xs[0], ys[0], 1.4D, abgr, 8);
            }
            case ACT_CIRCLE -> {
                ShapeDraw.ring(bb, pose, cx, cy, 5.0D, 0.9D, abgr, 20);
                ShapeDraw.fillCircle(bb, pose, cx, cy, 0.9D, abgr, 8);
            }
            case ACT_ERASE -> {
                double[] xs = {cx - 5, cx + 5, cx + 5, cx - 5};
                double[] ys = {cy - 3.5, cy - 3.5, cy + 3.5, cy + 3.5};
                ShapeDraw.strokePolyline(bb, pose, xs, ys, 0.8D, true, abgr);
                ShapeDraw.seg(bb, pose, cx - 2.5, cy - 3.5, cx - 2.5, cy + 3.5, 0.7D, abgr);
            }
            case ACT_PALETTE -> {
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 3; c++) {
                        ShapeDraw.fillCircle(bb, pose, cx - 4 + c * 4, cy - 2 + r * 4, 1.3D, abgr, 8);
                    }
                }
            }
            case ACT_SQUAD -> {
                ShapeDraw.ring(bb, pose, cx - 2.5, cy - 1, 2.6D, 0.8D, abgr, 12);
                ShapeDraw.ring(bb, pose, cx + 2.5, cy - 1, 2.6D, 0.8D, abgr, 12);
                ShapeDraw.seg(bb, pose, cx - 5.5, cy + 5.5, cx - 0.5, cy + 5.5, 0.9D, abgr);
                ShapeDraw.seg(bb, pose, cx + 0.5, cy + 5.5, cx + 5.5, cy + 5.5, 0.9D, abgr);
            }
            case ACT_EXPORT -> {
                ShapeDraw.seg(bb, pose, cx, cy + 5, cx, cy - 4, 1.0D, abgr);
                ShapeDraw.arrow(bb, pose, cx, cy - 5.5, 0, -1, 3.6D, 2.6D, abgr);
                ShapeDraw.seg(bb, pose, cx - 4.5, cy + 5.5, cx + 4.5, cy + 5.5, 0.9D, abgr);
            }
            case ACT_IMPORT -> {
                ShapeDraw.seg(bb, pose, cx, cy - 5, cx, cy + 4, 1.0D, abgr);
                ShapeDraw.arrow(bb, pose, cx, cy + 5.5, 0, 1, 3.6D, 2.6D, abgr);
                ShapeDraw.seg(bb, pose, cx - 4.5, cy + 5.5, cx + 4.5, cy + 5.5, 0.9D, abgr);
            }
            case ACT_ENEMY_ROUTE -> {
                // v4.0.10: hostile zigzag with TWO arrowheads - always drawn
                // in enemy red regardless of the toolbar icon tint
                int red = ShapeDraw.abgr(0xFFFF5252);
                double[] exs = {cx - 6, cx - 1.5, cx + 2, cx + 6};
                double[] eys = {cy + 4.5, cy - 1, cy + 2.5, cy - 4.5};
                ShapeDraw.strokeDashed(bb, pose, exs, eys, 0.8D, false, 2.5D, 1.8D, red);
                ShapeDraw.arrow(bb, pose, cx + 6, cy - 4.5, 0.55D, -0.83D, 4.2D, 2.6D, red);
                ShapeDraw.arrow(bb, pose, cx - 0.2, cy + 1.8, 0.55D, -0.83D, 3.4D, 2.0D, red);
            }
            default -> ShapeDraw.fillCircle(bb, pose, cx, cy, 3.0D, abgr, 10);
        }
        ShapeDraw.flush(bb);
    }
}
