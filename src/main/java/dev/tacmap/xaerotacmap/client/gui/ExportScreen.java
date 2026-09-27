package dev.tacmap.xaerotacmap.client.gui;

import java.nio.file.Path;
import java.util.List;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore;
import dev.tacmap.xaerotacmap.net.TacNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Export dialog (spec): write the annotations you can see to a
 * {@code .tacmap} file - either just your squad, or (OP only) every squad via
 * a server-side data pull.
 */
public final class ExportScreen extends Screen {

    private static final int PANEL_W = 224;
    private static final int BG = 0xD60D1114;
    private static final int BORDER = 0xFF2FA8B8;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int TEXT_DIM = 0xFF9FB4B8;
    private static final int OK = 0xFF7FE28A;
    private static final int ERR = 0xFFE28A8A;

    /** Last "export all" payload pushed by {@code ClientMarkerStore}. */
    private static volatile List<TacAnnotation> pendingAll;
    private static volatile boolean allRequested;

    private EditBox nameBox;
    private String status = "";
    private int statusColor = TEXT_DIM;

    public ExportScreen() {
        super(Component.translatable("xaerotacmap.export.title"));
    }

    /** Called by the client store when the server answers an export-all pull. */
    public static void acceptExportAll(List<TacAnnotation> annos) {
        pendingAll = annos;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - PANEL_W / 2;
        int top = 56;
        nameBox = new EditBox(this.font, left, top, PANEL_W - 8, 18,
                Component.translatable("xaerotacmap.export.filename"));
        nameBox.setMaxLength(48);
        nameBox.setValue("tacmap-export");
        // must be a RENDERABLE widget - plain addWidget() leaves the box invisible
        addRenderableWidget(nameBox);
        setFocused(nameBox);

        int half = (PANEL_W - 12) / 2;
        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.export.squad"),
                        b -> exportSquad()).bounds(left, top + 26, half, 18).build());
        if (ClientMarkerStore.isOp()) {
            addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.export.all"),
                            b -> exportAll()).bounds(left + half + 12, top + 26, half, 18).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> this.minecraft.setScreen(new SquadScreen()))
                .bounds(left, top + 54, PANEL_W - 8, 16).build());
    }

    private void exportSquad() {
        if (!ClientMarkerStore.inSquad()) {
            status = Component.translatable("xaerotacmap.annotate.need_squad").getString();
            statusColor = ERR;
            return;
        }
        doExport(ClientMarkerStore.markersAll(), "本队");
    }

    private void exportAll() {
        allRequested = true;
        pendingAll = null;
        status = Component.translatable("xaerotacmap.export.waiting").getString();
        statusColor = TEXT_DIM;
        TacNet.CHANNEL.sendToServer(new TacNet.ExportAllPkt());
    }

    private void doExport(List<TacAnnotation> markers, String tag) {
        try {
            String file = MarkerIO.sanitizeFileName(nameBox.getValue());
            Path target = MarkerIO.exportDir().resolve(file + ".tacmap");
            MarkerIO.write(markers, target, playerName());
            status = Component.translatable("xaerotacmap.export.done",
                    target.getFileName().toString(), markers.size()).getString();
            statusColor = OK;
        } catch (Exception ex) {
            status = Component.translatable("xaerotacmap.export.failed", ex.toString()).getString();
            statusColor = ERR;
        }
    }

    private String playerName() {
        return this.minecraft != null && this.minecraft.getUser() != null
                ? this.minecraft.getUser().getName() : "?";
    }

    @Override
    public void tick() {
        super.tick();
        if (allRequested && pendingAll != null) {
            List<TacAnnotation> data = pendingAll;
            pendingAll = null;
            allRequested = false;
            doExport(data, "全部");
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        int left = this.width / 2 - PANEL_W / 2;
        int top = 30;
        gg.fill(left - 6, top - 8, left + PANEL_W + 6, this.height - 40, BG);
        gg.fill(left - 6, top - 8, left + PANEL_W + 6, top - 7, BORDER);
        gg.fill(left - 6, this.height - 41, left + PANEL_W + 6, this.height - 40, BORDER);
        gg.fill(left - 6, top - 8, left - 5, this.height - 40, BORDER);
        gg.fill(left + PANEL_W + 5, top - 8, left + PANEL_W + 6, this.height - 40, BORDER);

        gg.drawCenteredString(this.font, this.title, this.width / 2, top, TEXT);
        gg.drawString(this.font,
                Component.translatable("xaerotacmap.export.dir_hint").getString(),
                left, top + 14, TEXT_DIM, true);
        if (!status.isEmpty()) {
            gg.drawString(this.font, status, left, top + 82, statusColor, true);
        }
        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics gg) {
        gg.fillGradient(0, 0, this.width, this.height, 0x66000000, 0x99000000);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
