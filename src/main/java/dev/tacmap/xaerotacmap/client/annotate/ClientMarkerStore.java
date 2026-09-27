package dev.tacmap.xaerotacmap.client.annotate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import dev.tacmap.xaerotacmap.net.TacNet;
import dev.tacmap.xaerotacmap.squad.Squad;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side mirror of the session squad + annotation state, updated by
 * S2C packets. All access is from the render/client thread (packets are
 * handled via {@code enqueueWork}).
 */
@Mod.EventBusSubscriber(modid = dev.tacmap.xaerotacmap.XaeroTacMap.MOD_ID, value = Dist.CLIENT)
public final class ClientMarkerStore {

    /** @nullable */ private static Squad mine;
    private static boolean isLeader;
    private static boolean isOp;
    private static UUID you;
    /** Squad name -> color, for the squad browser. */
    private static Map<String, Integer> roster = Collections.emptyMap();
    /** My squad's annotations (any dimension; filtered at render time). */
    private static final List<TacAnnotation> markers = new ArrayList<>();
    /** True once any SquadStatePkt arrived - lets the UI detect "no sync". */
    private static boolean syncAlive;

    private ClientMarkerStore() {
    }

    // ------------------------------------------------------------ packet application

    public static void applySquadState(TacNet.SquadStatePkt pkt) {
        roster = pkt.roster;
        mine = pkt.mine;
        isLeader = pkt.isLeader;
        isOp = pkt.isOp;
        you = pkt.you;
        syncAlive = true;
        // If we were kicked / squad disbanded while we had markers, clear them.
        if (mine == null) {
            markers.clear();
        }
    }

    public static void applyMarkerAdd(TacAnnotation anno) {
        if (mine == null) {
            return;
        }
        markers.removeIf(a -> a.id.equals(anno.id));
        markers.add(anno);
    }

    public static void applyMarkerDelete(UUID id) {
        markers.removeIf(a -> a.id.equals(id));
    }

    public static void applyMarkerReset(int scope) {
        markers.clear();
    }

    public static void applyMarkerReplace(List<TacAnnotation> annos) {
        markers.clear();
        markers.addAll(annos);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        markers.clear();
        mine = null;
        isLeader = false;
        isOp = false;
        you = null;
        roster = Collections.emptyMap();
        syncAlive = false;
        DrawingController.reset();
    }

    // ------------------------------------------------------------ queries

    /** @nullable the player's squad mirror. */
    public static Squad squad() {
        return mine;
    }

    public static boolean inSquad() {
        return mine != null;
    }

    public static boolean isLeader() {
        return isLeader;
    }

    public static boolean isOp() {
        return isOp;
    }

    public static UUID selfId() {
        return you;
    }

    public static Map<String, Integer> roster() {
        return roster;
    }

    /** The ARGB color used to render an annotation (enemy marks forced red). */
    public static int renderColor(TacAnnotation a) {
        if (a.isEnemyMark()) {
            return Squad.ENEMY_RED;
        }
        return mine == null ? 0xFFFFFFFF : mine.color;
    }

    public static int squadColor() {
        return mine == null ? 0xFF9FB4B8 : mine.color;
    }

    public static List<TacAnnotation> markersIn(String dimension) {
        if (markers.isEmpty()) {
            return List.of();
        }
        List<TacAnnotation> out = new ArrayList<>();
        for (TacAnnotation a : markers) {
            if (a.dimension.equals(dimension)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Full copy across all dimensions (used by the file exporter). */
    public static List<TacAnnotation> markersAll() {
        return List.copyOf(markers);
    }

    public static TacAnnotation findById(UUID id) {
        for (TacAnnotation a : markers) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    /** May the local player delete this annotation right now? (Server re-checks.) */
    public static boolean mayDelete(TacAnnotation a) {
        if (mine == null) {
            return false;
        }
        return (you != null && a.creator.equals(you)) || isLeader || isOp;
    }

    /** Sends a state refresh request (used when opening the squad screen). */
    public static void requestSync() {
        if (Minecraft.getInstance().getConnection() != null) {
            TacNet.CHANNEL.sendToServer(new TacNet.RequestSyncPkt());
        }
    }

    public static boolean syncAlive() {
        return syncAlive;
    }
}
