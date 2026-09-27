package dev.tacmap.xaerotacmap.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore;
import dev.tacmap.xaerotacmap.net.TacNet;
import dev.tacmap.xaerotacmap.squad.Squad;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Standalone squad panel (spec: independent UI). Create/join/leave squads,
 * transfer leadership, reset markers, and open the export/import screens.
 *
 * <p>Member rows are custom-drawn (click to select the transfer target);
 * action buttons are vanilla widgets. The layout rebuilds on init and reads
 * the live synced state each frame, so server updates appear instantly.</p>
 */
public final class SquadScreen extends Screen {

    private static final int PANEL_W = 224;
    private static final int BG = 0xD60D1114;
    private static final int BORDER = 0xFF2FA8B8;
    private static final int SEL_BG = 0x502FA8B8;
    private static final int TEXT = 0xFFE8ECEE;
    private static final int TEXT_DIM = 0xFF9FB4B8;
    private static final int WARN = 0xFFFFD54F;

    private EditBox nameBox;
    private Button transferBtn;
    private final List<UUID> rowOrder = new ArrayList<>();
    private final List<Integer> rowY = new ArrayList<>();
    private UUID selectedMember;
    private int lastMemberCount = -1;
    private boolean lastInSquad;
    private String warn = "";

    public SquadScreen() {
        super(Component.translatable("xaerotacmap.squad.title"));
    }

    @Override
    protected void init() {
        ClientMarkerStore.requestSync();
        rebuild();
    }

    private void rebuild() {
        // keep whatever the user typed across state-driven rebuilds
        String keep = nameBox != null ? nameBox.getValue() : "";
        this.clearWidgets();
        rowOrder.clear();
        rowY.clear();
        nameBox = null;
        transferBtn = null;
        int left = this.width / 2 - PANEL_W / 2;
        if (ClientMarkerStore.inSquad()) {
            buildInSquad(left);
        } else {
            buildNoSquad(left);
        }
        if (nameBox != null && !keep.isEmpty()) {
            nameBox.setValue(keep);
        }
        lastInSquad = ClientMarkerStore.inSquad();
        lastMemberCount = lastInSquad ? ClientMarkerStore.squad().viewMembers().size() : 0;
    }

    private void buildNoSquad(int left) {
        int top = 60;
        nameBox = new EditBox(this.font, left, top, PANEL_W - 8, 18,
                Component.translatable("xaerotacmap.squad.name"));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("xaerotacmap.squad.name"));
        // must be a RENDERABLE widget - plain addWidget() leaves the box invisible
        addRenderableWidget(nameBox);
        setFocused(nameBox);

        int half = (PANEL_W - 12) / 2;
        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.create"),
                        b -> sendSquadOp(true)).bounds(left, top + 24, half, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.join"),
                        b -> sendSquadOp(false)).bounds(left + half + 12, top + 24, half, 18).build());

        // existing squads: click to fill the name box
        int y = top + 52;
        Map<String, Integer> roster = ClientMarkerStore.roster();
        for (Map.Entry<String, Integer> e : roster.entrySet()) {
            if (y > this.height - 74) {
                break;
            }
            String name = e.getKey();
            addRenderableWidget(Button.builder(Component.literal(name),
                            b -> nameBox.setValue(name))
                    .bounds(left, y, PANEL_W - 8, 16).build());
            y += 18;
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> onClose()).bounds(left, this.height - 42, PANEL_W - 8, 16).build());
    }

    private void buildInSquad(int left) {
        Squad s = ClientMarkerStore.squad();
        int top = 60;
        boolean leader = ClientMarkerStore.isLeader();
        boolean op = ClientMarkerStore.isOp();

        // member rows are custom-drawn; record their geometry for hit testing
        int y = top + 30;
        for (Map.Entry<UUID, String> e : s.viewMembers().entrySet()) {
            if (y > this.height - 128) {
                break;
            }
            rowOrder.add(e.getKey());
            rowY.add(y);
            y += 18;
        }

        int by = Math.min(this.height - 118, y + 6);
        int half = (PANEL_W - 12) / 2;

        transferBtn = Button.builder(Component.translatable("xaerotacmap.squad.transfer"),
                        b -> {
                            if (selectedMember != null) {
                                TacNet.CHANNEL.sendToServer(new TacNet.TransferLeaderPkt(selectedMember));
                                selectedMember = null;
                            }
                        })
                .bounds(left, by, PANEL_W - 8, 16).build();
        addRenderableWidget(transferBtn);
        by += 18;

        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.leave"),
                        b -> TacNet.CHANNEL.sendToServer(new TacNet.LeaveSquadPkt()))
                .bounds(left, by, half, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.reset_squad"),
                        b -> TacNet.CHANNEL.sendToServer(new TacNet.ResetMarkersPkt(0)))
                .bounds(left + half + 12, by, half, 16).build());
        by += 18;
        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.export"),
                        b -> this.minecraft.setScreen(new ExportScreen()))
                .bounds(left, by, half, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.import"),
                        b -> this.minecraft.setScreen(new ImportScreen()))
                .bounds(left + half + 12, by, half, 16).build());
        by += 18;
        if (op) {
            addRenderableWidget(Button.builder(Component.translatable("xaerotacmap.squad.reset_all"),
                            b -> TacNet.CHANNEL.sendToServer(new TacNet.ResetMarkersPkt(1)))
                    .bounds(left, by, PANEL_W - 8, 16).build());
            by += 18;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> onClose()).bounds(left, by, PANEL_W - 8, 16).build());
    }

    private void sendSquadOp(boolean create) {
        if (nameBox == null) {
            return;
        }
        String value = nameBox.getValue().trim();
        if (value.isEmpty()) {
            // never fail silently - show the reason in the panel
            warn = Component.translatable("xaerotacmap.squad.need_name").getString();
            return;
        }
        warn = "";
        TacNet.CHANNEL.sendToServer(create
                ? new TacNet.CreateSquadPkt(value)
                : new TacNet.JoinSquadPkt(value));
    }

    @Override
    public void tick() {
        super.tick();
        // rebuild when joining/leaving or membership count changed
        boolean inSquad = ClientMarkerStore.inSquad();
        int count = inSquad ? ClientMarkerStore.squad().viewMembers().size() : 0;
        if (inSquad != lastInSquad || count != lastMemberCount) {
            rebuild();
        }
        if (transferBtn != null) {
            Squad s = ClientMarkerStore.squad();
            transferBtn.active = selectedMember != null && s != null
                    && !selectedMember.equals(s.leader);
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        int left = this.width / 2 - PANEL_W / 2;
        int top = 30;

        gg.fill(left - 6, top - 8, left + PANEL_W + 6, this.height - 20, BG);
        gg.fill(left - 6, top - 8, left + PANEL_W + 6, top - 7, BORDER);
        gg.fill(left - 6, this.height - 21, left + PANEL_W + 6, this.height - 20, BORDER);
        gg.fill(left - 6, top - 8, left - 5, this.height - 20, BORDER);
        gg.fill(left + PANEL_W + 5, top - 8, left + PANEL_W + 6, this.height - 20, BORDER);

        gg.drawCenteredString(this.font, this.title, this.width / 2, top, TEXT);

        if (!ClientMarkerStore.syncAlive()) {
            gg.drawString(this.font,
                    Component.translatable("xaerotacmap.squad.no_sync").getString(),
                    left, top + 16, WARN, true);
            super.render(gg, mouseX, mouseY, partialTick);
            return;
        }

        if (ClientMarkerStore.inSquad()) {
            Squad s = ClientMarkerStore.squad();
            gg.fill(left, top + 14, left + 10, top + 24, s.color);
            gg.drawString(this.font,
                    Component.translatable("xaerotacmap.squad.squad_tag", s.name).getString(),
                    left + 14, top + 14, TEXT, true);
            String role = ClientMarkerStore.isLeader()
                    ? Component.translatable("xaerotacmap.squad.role_leader").getString()
                    : (ClientMarkerStore.isOp()
                            ? Component.translatable("xaerotacmap.squad.role_op").getString() : "");
            if (!role.isEmpty()) {
                gg.drawString(this.font, role,
                        left + PANEL_W - 4 - this.font.width(role), top + 14, WARN, true);
            }
            // member rows
            for (int i = 0; i < rowOrder.size(); i++) {
                UUID id = rowOrder.get(i);
                int y = rowY.get(i);
                String name = s.viewMembers().get(id);
                if (name == null) {
                    continue;
                }
                boolean sel = id.equals(selectedMember);
                if (sel) {
                    gg.fill(left, y - 2, left + PANEL_W - 8, y + 14, SEL_BG);
                }
                String row = (s.isLeader(id) ? "★ " : "   ") + name;
                gg.drawString(this.font, row, left + 2, y, sel ? 0xFFFFFFFF : TEXT, true);
            }
        } else {
            gg.drawString(this.font,
                    Component.translatable("xaerotacmap.squad.need_squad_hint").getString(),
                    left, top + 16, TEXT_DIM, true);
            if (!warn.isEmpty()) {
                gg.drawString(this.font, warn, left, this.height - 56, WARN, true);
            }
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // member row selection (custom rows)
        if (button == 0 && ClientMarkerStore.inSquad()) {
            for (int i = 0; i < rowOrder.size(); i++) {
                int y = rowY.get(i);
                if (my >= y - 2 && my <= y + 14) {
                    int left = this.width / 2 - PANEL_W / 2;
                    if (mx >= left && mx <= left + PANEL_W - 8) {
                        UUID id = rowOrder.get(i);
                        selectedMember = id.equals(selectedMember) ? null : id;
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
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
