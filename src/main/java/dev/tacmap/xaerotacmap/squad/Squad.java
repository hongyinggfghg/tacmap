package dev.tacmap.xaerotacmap.squad;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server-side squad state. Session-only (cleared on server stop); squads are
 * independent - members only ever see their own squad's annotations.
 *
 * <p>Colors are assigned from a fixed 8-color palette by squad creation order
 * and cannot be customized (spec: per-squad fixed color). Enemy-contact
 * markers are always red regardless of this color.</p>
 */
public final class Squad {

    /** Fixed squad palette, indexed by creation order modulo length. */
    public static final int[] PALETTE = {
            0xFF4FC3F7, // light blue
            0xFF81C784, // green
            0xFFFFD54F, // amber
            0xFFBA68C8, // purple
            0xFFFFB74D, // orange
            0xFF4DD0E1, // cyan
            0xFFF06292, // pink
            0xFFAED581, // lime
    };

    /** The enemy-contact marker is forced to this red no matter the squad color. */
    public static final int ENEMY_RED = 0xFFFF5252;

    public static final int MAX_MEMBERS = 32;

    public final String name;
    public final int color;
    public UUID leader;
    /** Member id -> member display name, insertion ordered (leader first). */
    public final Map<UUID, String> members = new LinkedHashMap<>();

    public Squad(String name, int color, UUID leader, String leaderName) {
        this.name = name;
        this.color = color;
        this.leader = leader;
        this.members.put(leader, leaderName);
    }

    public boolean isLeader(UUID id) {
        return leader.equals(id);
    }

    public boolean isMember(UUID id) {
        return members.containsKey(id);
    }

    /** Adds a member if the squad is not full and the id is free. */
    public boolean addMember(UUID id, String name) {
        if (members.size() >= MAX_MEMBERS || members.containsKey(id)) {
            return false;
        }
        members.put(id, name);
        return true;
    }

    public void removeMember(UUID id) {
        members.remove(id);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, 32);
        buf.writeInt(color);
        buf.writeUUID(leader);
        buf.writeVarInt(members.size());
        for (Map.Entry<UUID, String> e : members.entrySet()) {
            buf.writeUUID(e.getKey());
            buf.writeUtf(e.getValue(), 64);
        }
    }

    /** Client-side reconstruction. */
    public static Squad decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(32);
        int color = buf.readInt();
        UUID leader = buf.readUUID();
        int n = buf.readVarInt();
        if (n < 0 || n > 256) {
            throw new IllegalArgumentException("bad member count " + n);
        }
        Squad s = new Squad(name, color, leader, "?");
        s.members.clear();
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            String memberName = buf.readUtf(64);
            s.members.put(id, memberName);
        }
        return s;
    }

    public Map<UUID, String> viewMembers() {
        return Collections.unmodifiableMap(members);
    }
}
