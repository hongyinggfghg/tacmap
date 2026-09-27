package dev.tacmap.xaerotacmap.client.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.mojang.datafixers.util.Pair;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore;
import dev.tacmap.xaerotacmap.net.TacNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Import dialog: lists {@code .tacmap} files from {@code config/tacmap_exports/};
 * clicking one re-sends every marker in it as fresh annotations of the local
 * player (server assigns them to the player's squad).
 */
public final class ImportScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int BG = 0xD60D1114;
    private static final int BORDER = 0xFF2FA8B8;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int TEXT_DIM = 0xFF9FB4B8;
    private static final int OK = 0xFF7FE28A;
    private static final int ERR = 0xFFE28A8A;
    private static final int MAX_IMPORT = 300;

    private final List<Path> files = MarkerIO.listFiles();
    private List<Path> shown = List.of();
    private String status = "";
    private int statusColor = TEXT_DIM;

    public ImportScreen() {
        super(Component.translatable("xaerotacmap.import.title"));
    }

    @Override
    protected void init() {
        int left = this.width / 2 - PANEL_W / 2;
        int top = 56;
        shown = files.subList(0, Math.min(files.size(), Math.max(1, (this.height - top - 96) / 18)));
        for (int i = 0; i < shown.size(); i++) {
            Path file = shown.get(i);
            String name = file.getFileName().toString();
            addRenderableWidget(Button.builder(Component.literal(name),
                            b -> importFile(file))
                    .bounds(left, top + i * 18, PANEL_W - 8, 16).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> this.minecraft.setScreen(new SquadScreen()))
                .bounds(left, this.height - 42, PANEL_W - 8, 16).build());
        if (files.isEmpty()) {
            status = Component.translatable("xaerotacmap.import.empty").getString();
            statusColor = TEXT_DIM;
        }
    }

    private void importFile(Path file) {
        if (!ClientMarkerStore.inSquad()) {
            status = Component.translatable("xaerotacmap.annotate.need_squad").getString();
            statusColor = ERR;
            return;
        }
        try {
            Pair<List<TacAnnotation>, String> data = MarkerIO.read(file);
            List<TacAnnotation> markers = data.getFirst();
            if (markers.size() > MAX_IMPORT) {
                markers = markers.subList(0, MAX_IMPORT);
            }
            UUID self = ClientMarkerStore.selfId();
            String selfName = this.minecraft.getUser() != null
                    ? this.minecraft.getUser().getName() : "?";
            int sent = 0;
            for (TacAnnotation a : markers) {
                TacAnnotation fresh = new TacAnnotation(UUID.randomUUID(), a.shape, a.symbol,
                        a.dimension, a.xs, a.zs, a.radius, a.label, self, selfName);
                TacNet.CHANNEL.sendToServer(new TacNet.AddMarkerPkt(fresh));
                sent++;
            }
            status = Component.translatable("xaerotacmap.import.done", sent).getString();
            statusColor = OK;
        } catch (Exception ex) {
            status = Component.translatable("xaerotacmap.import.failed", ex.toString()).getString();
            statusColor = ERR;
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
                Component.translatable("xaerotacmap.import.dir_hint").getString(),
                left, top + 14, TEXT_DIM, true);
        if (!status.isEmpty()) {
            gg.drawString(this.font, status, left, this.height - 56, statusColor, true);
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
