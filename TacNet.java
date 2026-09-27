package dev.tacmap.xaerotacmap.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.squad.Squad;
import dev.tacmap.xaerotacmap.squad.SquadManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkEvent;

/**
 * Session sync channel for squads & map annotations.
 *
 * <p>The channel accepts any remote version (including ABSENT) so clients can
 * still join vanilla or non-TacMap servers - the annotation features simply
 * stay dormant there (the client UI shows "sync unavailable").</p>
 */
public final class TacNet {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(XaeroTacMap.MOD_ID, "main"),
            () -> PROTOCOL,
            version -> true,
            version -> true);

    private static int registered = 0;

    private TacNet() {
    }

    /** Must be called once from the mod constructor (both sides). */
    public static synchronized void register() {
        if (registered > 0) {
            return;
        }
        registered = 1;
        // C2S
        CHANNEL.registerMessage(0, CreateSquadPkt.class,
                CreateSquadPkt::encode, CreateSquadPkt::decode, CreateSquadPkt::handle);
        CHANNEL.registerMessage(1, JoinSquadPkt.class,
                JoinSquadPkt::encode, JoinSquadPkt::decode, JoinSquadPkt::handle);
        CHANNEL.registerMessage(2, LeaveSquadPkt.class,
                LeaveSquadPkt::encode, LeaveSquadPkt::decode, LeaveSquadPkt::handle);
        CHANNEL.registerMessage(3, TransferLeaderPkt.class,
                TransferLeaderPkt::encode, TransferLeaderPkt::decode, TransferLeaderPkt::handle);
        CHANNEL.registerMessage(4, AddMarkerPkt.class,
                AddMarkerPkt::encode, AddMarkerPkt::decode, AddMarkerPkt::handle);
        CHANNEL.registerMessage(5, DeleteMarkerPkt.class,
                DeleteMarkerPkt::encode, DeleteMarkerPkt::decode, DeleteMarkerPkt::handle);
        CHANNEL.registerMessage(6, ResetMarkersPkt.class,
                ResetMarkersPkt::encode, ResetMarkersPkt::decode, ResetMarkersPkt::handle);
        CHANNEL.registerMessage(7, RequestSyncPkt.class,
                RequestSyncPkt::encode, RequestSyncPkt::decode, RequestSyncPkt::handle);
        // S2C
        CHANNEL.registerMessage(8, SquadStatePkt.class,
                SquadStatePkt::encode, SquadStatePkt::decode, SquadStatePkt::handle);
        CHANNEL.registerMessage(9, MarkerAddPkt.class,
                MarkerAddPkt::encode, MarkerAddPkt::decode, MarkerAddPkt::handle);
        CHANNEL.registerMessage(10, MarkerDeletePkt.class,
                MarkerDeletePkt::encode, MarkerDeletePkt::decode, MarkerDeletePkt::handle);
        CHANNEL.registerMessage(11, MarkerResetPkt.class,
                MarkerResetPkt::encode, MarkerResetPkt::decode, MarkerResetPkt::handle);
        CHANNEL.registerMessage(12, MarkerReplacePkt.class,
                MarkerReplacePkt::encode, MarkerReplacePkt::decode, MarkerReplacePkt::handle);
        CHANNEL.registerMessage(13, ExportAllPkt.class,
                ExportAllPkt::encode, ExportAllPkt::decode, ExportAllPkt::handle);
        CHANNEL.registerMessage(14, ExportDataPkt.class,
                ExportDataPkt::encode, ExportDataPkt::decode, ExportDataPkt::handle);
        XaeroTacMap.LOGGER.info("[TacNet] Session sync channel registered (15 messages).");
    }

    // ------------------------------------------------------------ send helpers

    public static void sendSyncAll(ServerPlayer p) {
        SquadStatePkt state = SquadManager.snapshot(p);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), state);
        List<TacAnnotation> markers = state.mine == null
                ? List.of()
                : SquadManager.sessionMarkersOf(state.mine);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new MarkerReplacePkt(markers));
    }

    public static void sendMarkerAdd(ServerPlayer p, TacAnnotation anno) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new MarkerAddPkt(anno));
    }

    public static void sendMarkerDelete(ServerPlayer p, UUID id) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new MarkerDeletePkt(id));
    }

    public static void sendMarkerReset(ServerPlayer p, int scope) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new MarkerResetPkt(scope));
    }

    public static void sendMarkerReplace(ServerPlayer p, List<TacAnnotation> annos) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new MarkerReplacePkt(annos));
    }

    private static void serverError(ServerPlayer p, String err) {
        if (err != null) {
            p.displayClientMessage(Component.literal("[TacMap] " + err).withStyle(ChatFormatting.RED), false);
        }
    }

    // ============================================================ packets

    /** Squad roster snapshot for one player. */
    public static final class SquadStatePkt {
        /** Squad name -> color, creation ordered. */
        public final Map<String, Integer> roster;
        /** The receiving player's squad, or null when squad-less. */
        public final Squad mine;
        public final boolean isLeader;
        public final boolean isOp;
        public final UUID you;

        public SquadStatePkt(Map<String, Integer> roster, Squad mine,
                             boolean isLeader, boolean isOp, UUID you) {
            this.roster = roster;
            this.mine = mine;
            this.isLeader = isLeader;
            this.isOp = isOp;
            this.you = you;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(roster.size());
            for (Map.Entry<String, Integer> e : roster.entrySet()) {
                buf.writeUtf(e.getKey(), 32);
                buf.writeInt(e.getValue());
            }
            buf.writeBoolean(mine != null);
            if (mine != null) {
                mine.encode(buf);
            }
            buf.writeBoolean(isLeader);
            buf.writeBoolean(isOp);
            buf.writeUUID(you);
        }

        public static SquadStatePkt decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > 256) {
                throw new IllegalArgumentException("bad roster size " + n);
            }
            Map<String, Integer> roster = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                String name = buf.readUtf(32);
                int color = buf.readInt();
                roster.put(name, color);
            }
            Squad mine = null;
            if (buf.readBoolean()) {
                mine = Squad.decode(buf);
            }
            boolean leader = buf.readBoolean();
            boolean op = buf.readBoolean();
            UUID you = buf.readUUID();
            return new SquadStatePkt(roster, mine, leader, op, you);
        }

        public static void handle(SquadStatePkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore
                                    .applySquadState(msg)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class CreateSquadPkt {
        public final String name;

        public CreateSquadPkt(String name) {
            this.name = name;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 32);
        }

        public static CreateSquadPkt decode(FriendlyByteBuf buf) {
            return new CreateSquadPkt(buf.readUtf(32));
        }

        public static void handle(CreateSquadPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    serverError(sender, SquadManager.createSquad(sender, msg.name));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class JoinSquadPkt {
        public final String name;

        public JoinSquadPkt(String name) {
            this.name = name;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 32);
        }

        public static JoinSquadPkt decode(FriendlyByteBuf buf) {
            return new JoinSquadPkt(buf.readUtf(32));
        }

        public static void handle(JoinSquadPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    serverError(sender, SquadManager.joinSquad(sender, msg.name));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class LeaveSquadPkt {

        public LeaveSquadPkt() {
        }

        public void encode(FriendlyByteBuf buf) {
        }

        public static LeaveSquadPkt decode(FriendlyByteBuf buf) {
            return new LeaveSquadPkt();
        }

        public static void handle(LeaveSquadPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    SquadManager.leaveSquad(sender);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class TransferLeaderPkt {
        public final UUID target;

        public TransferLeaderPkt(UUID target) {
            this.target = target;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(target);
        }

        public static TransferLeaderPkt decode(FriendlyByteBuf buf) {
            return new TransferLeaderPkt(buf.readUUID());
        }

        public static void handle(TransferLeaderPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    serverError(sender, SquadManager.transferLeader(sender, msg.target));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class AddMarkerPkt {
        public final TacAnnotation anno;

        public AddMarkerPkt(TacAnnotation anno) {
            this.anno = anno;
        }

        public void encode(FriendlyByteBuf buf) {
            anno.encode(buf);
        }

        public static AddMarkerPkt decode(FriendlyByteBuf buf) {
            return new AddMarkerPkt(TacAnnotation.decode(buf));
        }

        public static void handle(AddMarkerPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    serverError(sender, SquadManager.addMarker(sender, msg.anno));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class DeleteMarkerPkt {
        public final UUID id;

        public DeleteMarkerPkt(UUID id) {
            this.id = id;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(id);
        }

        public static DeleteMarkerPkt decode(FriendlyByteBuf buf) {
            return new DeleteMarkerPkt(buf.readUUID());
        }

        public static void handle(DeleteMarkerPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    serverError(sender, SquadManager.deleteMarker(sender, msg.id));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class ResetMarkersPkt {
        public final int scope;

        public ResetMarkersPkt(int scope) {
            this.scope = scope;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeByte(scope);
        }

        public static ResetMarkersPkt decode(FriendlyByteBuf buf) {
            return new ResetMarkersPkt(buf.readByte());
        }

        public static void handle(ResetMarkersPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    serverError(sender, SquadManager.resetMarkers(sender, msg.scope));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class RequestSyncPkt {

        public RequestSyncPkt() {
        }

        public void encode(FriendlyByteBuf buf) {
        }

        public static RequestSyncPkt decode(FriendlyByteBuf buf) {
            return new RequestSyncPkt();
        }

        public static void handle(RequestSyncPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null) {
                    sendSyncAll(sender);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class MarkerAddPkt {
        public final TacAnnotation anno;

        public MarkerAddPkt(TacAnnotation anno) {
            this.anno = anno;
        }

        public void encode(FriendlyByteBuf buf) {
            anno.encode(buf);
        }

        public static MarkerAddPkt decode(FriendlyByteBuf buf) {
            return new MarkerAddPkt(TacAnnotation.decode(buf));
        }

        public static void handle(MarkerAddPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore
                                    .applyMarkerAdd(msg.anno)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class MarkerDeletePkt {
        public final UUID id;

        public MarkerDeletePkt(UUID id) {
            this.id = id;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(id);
        }

        public static MarkerDeletePkt decode(FriendlyByteBuf buf) {
            return new MarkerDeletePkt(buf.readUUID());
        }

        public static void handle(MarkerDeletePkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore
                                    .applyMarkerDelete(msg.id)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class MarkerResetPkt {
        /** 0 = my squad was reset, 1 = everything was reset. */
        public final int scope;

        public MarkerResetPkt(int scope) {
            this.scope = scope;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeByte(scope);
        }

        public static MarkerResetPkt decode(FriendlyByteBuf buf) {
            return new MarkerResetPkt(buf.readByte());
        }

        public static void handle(MarkerResetPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore
                                    .applyMarkerReset(msg.scope)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class MarkerReplacePkt {
        public final List<TacAnnotation> annos;

        public MarkerReplacePkt(List<TacAnnotation> annos) {
            this.annos = annos == null ? List.of() : annos;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(annos.size());
            for (TacAnnotation a : annos) {
                a.encode(buf);
            }
        }

        public static MarkerReplacePkt decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > 1024) {
                throw new IllegalArgumentException("bad marker list size " + n);
            }
            List<TacAnnotation> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(TacAnnotation.decode(buf));
            }
            return new MarkerReplacePkt(list);
        }

        public static void handle(MarkerReplacePkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.tacmap.xaerotacmap.client.annotate.ClientMarkerStore
                                    .applyMarkerReplace(msg.annos)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** OP-only request: pulls every squad's annotations for file export. */
    public static final class ExportAllPkt {

        public ExportAllPkt() {
        }

        public void encode(FriendlyByteBuf buf) {
        }

        public static ExportAllPkt decode(FriendlyByteBuf buf) {
            return new ExportAllPkt();
        }

        public static void handle(ExportAllPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender != null && sender.hasPermissions(2)) {
                    List<TacAnnotation> all = SquadManager.exportAllMarkers(sender);
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new ExportDataPkt(all));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Server answer to {@link ExportAllPkt} - routed to the export dialog. */
    public static final class ExportDataPkt {
        public final List<TacAnnotation> annos;

        public ExportDataPkt(List<TacAnnotation> annos) {
            this.annos = annos == null ? List.of() : annos;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(annos.size());
            for (TacAnnotation a : annos) {
                a.encode(buf);
            }
        }

        public static ExportDataPkt decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n < 0 || n > 4096) {
                throw new IllegalArgumentException("bad export size " + n);
            }
            List<TacAnnotation> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(TacAnnotation.decode(buf));
            }
            return new ExportDataPkt(list);
        }

        public static void handle(ExportDataPkt msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.tacmap.xaerotacmap.client.gui.ExportScreen
                                    .acceptExportAll(msg.annos)));
            ctx.get().setPacketHandled(true);
        }
    }
}
