package dev.tacmap.xaerotacmap.squad;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.tacmap.xaerotacmap.XaeroTacMap;
import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.net.TacNet;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side squad + annotation session state. Everything lives in memory
 * only (spec: session-volatile, persistence via manual export files) and is
 * wiped when the server stops.
 *
 * <p>Permission model (spec):</p>
 * <ul>
 *   <li>Players must join a squad before drawing; each squad is independent
 *       and its annotations are only ever visible to its own members.</li>
 *   <li>A member may delete annotations they created themselves; the squad
 *       leader may delete anything in the squad; an OP has leader rights in
 *       every squad plus reset-all.</li>
 *   <li>Squad creator becomes leader and may transfer leadership; an OP can
 *       force-transfer via {@code /tacmap squad leader <player>}.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = XaeroTacMap.MOD_ID)
public final class SquadManager {

    public static final int MAX_SQUADS = 32;
    public static final int MAX_MARKERS_PER_SQUAD = 500;
    public static final int MAX_LABEL = 24;

    /** Squad name (lowercased key) -> squad. */
    private static final Map<String, Squad> squads = new LinkedHashMap<>();
    /** Player id -> squad key they belong to. */
    private static final Map<UUID, String> memberOf = new LinkedHashMap<>();
    /** Squad key -> that squad's annotations. */
    private static final Map<String, List<TacAnnotation>> markers = new LinkedHashMap<>();

    private SquadManager() {
    }

    // ------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        squads.clear();
        memberOf.clear();
        markers.clear();
        XaeroTacMap.LOGGER.info("[TacMap] Session squads & annotations cleared (server stopped).");
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            TacNet.sendSyncAll(sp);
        }
    }

    // ------------------------------------------------------------ helpers

    private static boolean isOp(ServerPlayer p) {
        return p.hasPermissions(2);
    }

    private static Squad squadOf(UUID playerId) {
        String key = memberOf.get(playerId);
        return key == null ? null : squads.get(key);
    }

    private static ServerPlayer online(MinecraftServer server, UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    private static void broadcastSquadStateToAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            TacNet.sendSyncAll(p);
        }
    }

    private static void broadcastMarkersToSquad(MinecraftServer server, Squad squad) {
        List<TacAnnotation> list = markers.get(squad.name.toLowerCase(java.util.Locale.ROOT));
        for (UUID id : squad.members.keySet()) {
            ServerPlayer p = online(server, id);
            if (p != null) {
                TacNet.sendMarkerReplace(p, list);
            }
        }
    }

    private static void chat(ServerPlayer p, String text, ChatFormatting color) {
        p.displayClientMessage(Component.literal("[TacMap] " + text).withStyle(color), false);
    }

    // ------------------------------------------------------------ squad ops

    /** Creates a squad; creator becomes leader. Returns error text or null on success. */
    public static String createSquad(ServerPlayer creator, String rawName) {
        String name = sanitizeName(rawName);
        if (name == null) {
            return "小队名需 1-16 个字符（不含空格）";
        }
        if (squads.size() >= MAX_SQUADS) {
            return "小队数量已达上限（" + MAX_SQUADS + "）";
        }
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (squads.containsKey(key)) {
            return "小队已存在：" + name;
        }
        if (memberOf.containsKey(creator.getUUID())) {
            return "你已在小队中，请先退出当前小队";
        }
        int color = Squad.PALETTE[squads.size() % Squad.PALETTE.length];
        Squad s = new Squad(name, color, creator.getUUID(), creator.getGameProfile().getName());
        squads.put(key, s);
        markers.put(key, new ArrayList<>());
        memberOf.put(creator.getUUID(), key);
        broadcastSquadStateToAll(creator.server);
        chat(creator, "小队已创建：" + name + "（你是队长）", ChatFormatting.GREEN);
        return null;
    }

    /** Joins an existing squad. Returns error text or null on success. */
    public static String joinSquad(ServerPlayer joiner, String rawName) {
        String name = sanitizeName(rawName);
        if (name == null) {
            return "小队名需 1-16 个字符";
        }
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Squad s = squads.get(key);
        if (s == null) {
            return "找不到小队：" + name;
        }
        if (memberOf.containsKey(joiner.getUUID())) {
            return "你已在小队中，请先退出当前小队";
        }
        if (!s.addMember(joiner.getUUID(), joiner.getGameProfile().getName())) {
            return "小队人数已满（" + Squad.MAX_MEMBERS + "）";
        }
        memberOf.put(joiner.getUUID(), key);
        broadcastSquadStateToAll(joiner.server);
        chat(joiner, "已加入小队：" + s.name, ChatFormatting.GREEN);
        return null;
    }

    /** Leaves the current squad. Leader leaving auto-transfers leadership. */
    public static void leaveSquad(ServerPlayer p) {
        Squad s = squadOf(p.getUUID());
        if (s == null) {
            chat(p, "你不在任何小队中", ChatFormatting.YELLOW);
            return;
        }
        UUID id = p.getUUID();
        s.removeMember(id);
        memberOf.remove(id);
        boolean wasLeader = s.leader.equals(id);
        if (s.members.isEmpty()) {
            squads.remove(s.name.toLowerCase(java.util.Locale.ROOT));
            markers.remove(s.name.toLowerCase(java.util.Locale.ROOT));
            chat(p, "已退出，小队「" + s.name + "」已解散", ChatFormatting.YELLOW);
        } else {
            if (wasLeader) {
                UUID next = s.members.keySet().iterator().next();
                s.leader = next;
                ServerPlayer np = online(p.server, next);
                chat(p, "已退出小队「" + s.name + "」", ChatFormatting.YELLOW);
                if (np != null) {
                    chat(np, "队长已离开，你成为新队长", ChatFormatting.GREEN);
                }
            } else {
                chat(p, "已退出小队「" + s.name + "」", ChatFormatting.YELLOW);
            }
            broadcastMarkersToSquad(p.server, s);
        }
        broadcastSquadStateToAll(p.server);
    }

    /** Leader (or OP) transfers leadership to another member of the same squad. */
    public static String transferLeader(ServerPlayer actor, UUID target) {
        Squad s = squadOf(actor.getUUID());
        if (s == null) {
            return "你不在任何小队中";
        }
        if (!s.isLeader(actor.getUUID()) && !isOp(actor)) {
            return "只有队长可以移交队长";
        }
        if (!s.isMember(target)) {
            return "目标不是本队成员";
        }
        s.leader = target;
        broadcastSquadStateToAll(actor.server);
        ServerPlayer t = online(actor.server, target);
        if (t != null) {
            chat(t, "你现在是「" + s.name + "」的队长", ChatFormatting.GREEN);
        }
        return null;
    }

    /** OP command: force-transfer leadership of the target's squad to the target. */
    public static String forceLeader(ServerPlayer op, ServerPlayer target) {
        Squad s = squadOf(target.getUUID());
        if (s == null) {
            return target.getGameProfile().getName() + " 不在任何小队中";
        }
        s.leader = target.getUUID();
        broadcastSquadStateToAll(op.server);
        chat(op, "已将「" + s.name + "」队长强制指定为 " + target.getGameProfile().getName(),
                ChatFormatting.GREEN);
        chat(target, "OP 将你指定为「" + s.name + "」的队长", ChatFormatting.GREEN);
        return null;
    }

    // ------------------------------------------------------------ marker ops

    /** Adds an annotation to the sender's squad. Returns error text or null. */
    public static String addMarker(ServerPlayer sender, TacAnnotation anno) {
        Squad s = squadOf(sender.getUUID());
        if (s == null) {
            return "请先在小队面板创建/加入小队";
        }
        String key = s.name.toLowerCase(java.util.Locale.ROOT);
        List<TacAnnotation> list = markers.get(key);
        if (list.size() >= MAX_MARKERS_PER_SQUAD) {
            return "本队标注已达上限（" + MAX_MARKERS_PER_SQUAD + "）";
        }
        TacAnnotation fixed = new TacAnnotation(anno.id, anno.shape, anno.symbol, anno.dimension,
                anno.xs, anno.zs, anno.radius, trimLabel(anno.label),
                sender.getUUID(), sender.getGameProfile().getName());
        list.add(fixed);
        for (UUID id : s.members.keySet()) {
            ServerPlayer p = online(sender.server, id);
            if (p != null) {
                TacNet.sendMarkerAdd(p, fixed);
            }
        }
        return null;
    }

    /**
     * Deletes an annotation by id: the creator may always delete their own,
     * the squad leader (and OP) may delete anything inside their squad.
     */
    public static String deleteMarker(ServerPlayer sender, UUID markerId) {
        Squad s = squadOf(sender.getUUID());
        if (s == null) {
            return "你不在任何小队中";
        }
        String key = s.name.toLowerCase(java.util.Locale.ROOT);
        List<TacAnnotation> list = markers.get(key);
        TacAnnotation target = null;
        for (TacAnnotation a : list) {
            if (a.id.equals(markerId)) {
                target = a;
                break;
            }
        }
        if (target == null) {
            return "标注不存在（可能已被删除）";
        }
        boolean mayDelete = target.creator.equals(sender.getUUID())
                || s.isLeader(sender.getUUID()) || isOp(sender);
        if (!mayDelete) {
            return "只能删除自己绘制的标注（队长可删除本队任意标注）";
        }
        list.remove(target);
        for (UUID id : s.members.keySet()) {
            ServerPlayer p = online(sender.server, id);
            if (p != null) {
                TacNet.sendMarkerDelete(p, markerId);
            }
        }
        return null;
    }

    /** Reset scope for {@link #resetMarkers}. */
    public static final int RESET_SQUAD = 0;
    public static final int RESET_ALL = 1;

    /** Leader resets own squad (or OP resets everything). Returns error text or null. */
    public static String resetMarkers(ServerPlayer sender, int scope) {
        MinecraftServer server = sender.server;
        if (scope == RESET_ALL) {
            if (!isOp(sender)) {
                return "只有 OP 可以重置全部标注";
            }
            for (List<TacAnnotation> l : markers.values()) {
                l.clear();
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                TacNet.sendMarkerReset(p, RESET_ALL);
            }
            chat(sender, "已重置全部小队的标注", ChatFormatting.YELLOW);
            return null;
        }
        Squad s = squadOf(sender.getUUID());
        if (s == null) {
            return "你不在任何小队中";
        }
        if (!s.isLeader(sender.getUUID()) && !isOp(sender)) {
            return "只有队长可以重置本队标注";
        }
        markers.get(s.name.toLowerCase(java.util.Locale.ROOT)).clear();
        broadcastMarkersToSquad(server, s);
        chat(sender, "已重置本队全部标注", ChatFormatting.YELLOW);
        return null;
    }

    /** OP command: wipe one squad's annotations by (fuzzy) name. */
    public static String clearSquadMarkers(ServerPlayer op, String rawName) {
        String key = rawName.trim().toLowerCase(java.util.Locale.ROOT);
        List<TacAnnotation> list = markers.get(key);
        if (list == null) {
            // fuzzy: prefix match
            for (Map.Entry<String, List<TacAnnotation>> e : markers.entrySet()) {
                if (e.getKey().startsWith(key)) {
                    key = e.getKey();
                    list = e.getValue();
                    break;
                }
            }
        }
        if (list == null) {
            return "找不到小队：" + rawName;
        }
        list.clear();
        Squad s = squads.get(key);
        if (s != null) {
            broadcastMarkersToSquad(op.server, s);
        }
        return null;
    }

    // ------------------------------------------------------------ data out

    /** Snapshot for one player: own squad (may be null) + global squad list. */
    public static TacNet.SquadStatePkt snapshot(ServerPlayer p) {
        Map<String, Integer> roster = new LinkedHashMap<>();
        for (Squad s : squads.values()) {
            roster.put(s.name, s.color);
        }
        Squad mine = squadOf(p.getUUID());
        return new TacNet.SquadStatePkt(roster, mine, mine != null && mine.isLeader(p.getUUID()),
                isOp(p), p.getUUID());
    }

    /** Live session marker list of the given squad (read-only view for sync). */
    public static List<TacAnnotation> sessionMarkersOf(Squad mine) {
        if (mine == null) {
            return List.of();
        }
        List<TacAnnotation> list = markers.get(mine.name.toLowerCase(java.util.Locale.ROOT));
        return list == null ? List.of() : List.copyOf(list);
    }

    /** OP only: every squad's annotations, for the export-all data pull. */
    public static List<TacAnnotation> exportAllMarkers(ServerPlayer op) {
        if (!isOp(op)) {
            return List.of();
        }
        List<TacAnnotation> all = new ArrayList<>();
        for (List<TacAnnotation> l : markers.values()) {
            all.addAll(l);
        }
        return all;
    }

    public static Collection<Squad> viewSquads() {
        return squads.values();
    }

    // ------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("tacmap")
                .then(net.minecraft.commands.Commands.literal("squad")
                        .then(net.minecraft.commands.Commands.literal("list")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    if (squads.isEmpty()) {
                                        chat(p, "当前没有小队", ChatFormatting.YELLOW);
                                    } else {
                                        for (Squad s : squads.values()) {
                                            chat(p, "小队「" + s.name + "」 成员 " + s.members.size()
                                                    + " 人 队长 " + s.members.get(s.leader), ChatFormatting.AQUA);
                                        }
                                    }
                                    return 1;
                                }))
                        .then(net.minecraft.commands.Commands.literal("leader")
                                .requires(src -> src.hasPermission(2))
                                .then(net.minecraft.commands.Commands.argument("player",
                                        net.minecraft.commands.arguments.EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer op = ctx.getSource().getPlayerOrException();
                                            ServerPlayer target =
                                                    net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
                                            String err = forceLeader(op, target);
                                            if (err != null) {
                                                chat(op, err, ChatFormatting.RED);
                                                return 0;
                                            }
                                            return 1;
                                        })))
                        .then(net.minecraft.commands.Commands.literal("clear")
                                .requires(src -> src.hasPermission(2))
                                .then(net.minecraft.commands.Commands.argument("squad",
                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer op = ctx.getSource().getPlayerOrException();
                                            String name = com.mojang.brigadier.arguments.StringArgumentType
                                                    .getString(ctx, "squad");
                                            String err = clearSquadMarkers(op, name);
                                            if (err != null) {
                                                chat(op, err, ChatFormatting.RED);
                                                return 0;
                                            }
                                            chat(op, "已清除该小队全部标注", ChatFormatting.YELLOW);
                                            return 1;
                                        })))));
    }

    // ------------------------------------------------------------ misc

    private static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().replaceAll("\\s+", "");
        if (name.isEmpty() || name.length() > 16 || name.contains("§")) {
            return null;
        }
        return name;
    }

    private static String trimLabel(String label) {
        if (label == null) {
            return "";
        }
        String l = label.trim();
        if (l.length() > MAX_LABEL) {
            l = l.substring(0, MAX_LABEL);
        }
        return l;
    }
}
