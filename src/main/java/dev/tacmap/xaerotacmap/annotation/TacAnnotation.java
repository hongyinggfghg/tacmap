package dev.tacmap.xaerotacmap.annotation;

import java.util.Arrays;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;

/**
 * A single map annotation drawn on the Xaero world map by a squad member.
 *
 * <p>Coordinates are stored as RAW world blocks of {@link #dimension} - the
 * renderer divides by the dimension divisor (nether 8, else 1) exactly like
 * Xaero divides the player arrow position. Session-only: never written to the
 * world save; persistence happens exclusively through manual export files.</p>
 */
public final class TacAnnotation {

    /** Geometry of the annotation. */
    public enum ShapeType {
        POINT, ROUTE, POLYGON, CIRCLE
    }

    /**
     * Icon drawn for POINT annotations (tactical pack + basic geometric set).
     * Names resolve through lang keys {@code xaerotacmap.symbol.<lowercase>}.
     *
     * <p>v4.0.6: the three ENEMY_* variants were APPENDED (never insert -
     * ordinals travel over the network via {@link #byId}, and inserting would
     * shift every existing id). Old clients receiving an unknown ordinal
     * degrade gracefully through floorMod; export files use {@link #name()}.
     * v4.0.10: ENEMY_ROUTE appended the same way - a ShapeType.ROUTE drawn
     * with this symbol renders as the red enemy axis-of-advance arrow chain
     * (every segment gets a directional arrowhead).</p>
     */
    public enum Symbol {
        // --- tactical pack (16) ---
        TARGET, RALLY, AMBUSH, SNIPER, OBS, CP, SUPPLY, MEDIC, AMMO,
        LZ, DZ, BASE, CHECKPOINT, ENEMY, SUSPECT, FLAG,
        // --- basic geometric set (5) ---
        GEO_CIRCLE, GEO_SQUARE, GEO_DIAMOND, GEO_TRIANGLE, GEO_STAR,
        // --- v4.0.6 enemy-objective set (3): always-red variants of the
        //     friendly rally/base/star icons for marking HOSTILE positions ---
        ENEMY_RALLY, ENEMY_BASE, ENEMY_STAR,
        // --- v4.0.10 enemy route (1): always-red ROUTE shape drawn as a
        //     solid line with arrowheads on every segment (axis of advance) ---
        ENEMY_ROUTE;

        public String langKey() {
            return "xaerotacmap.symbol." + name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Symbol byId(int id) {
            Symbol[] v = values();
            return v[Math.floorMod(id, v.length)];
        }
    }

    /** The annotation id - unique per creation, regenerated on import. */
    public final UUID id;
    public final ShapeType shape;
    public final Symbol symbol;
    /** Dimension id string, e.g. {@code minecraft:overworld}. */
    public final String dimension;
    /** Raw world X of each vertex (POINT: single, CIRCLE: center). */
    public final double[] xs;
    /** Raw world Z of each vertex. */
    public final double[] zs;
    /** World-block radius, CIRCLE only; unused otherwise. */
    public final double radius;
    /** Display label; may be empty for unnamed shapes. */
    public final String label;
    public final UUID creator;
    public final String creatorName;

    public TacAnnotation(UUID id, ShapeType shape, Symbol symbol, String dimension,
                         double[] xs, double[] zs, double radius,
                         String label, UUID creator, String creatorName) {
        this.id = id;
        this.shape = shape;
        this.symbol = symbol;
        this.dimension = dimension;
        this.xs = xs;
        this.zs = zs;
        this.radius = radius;
        this.label = label == null ? "" : label;
        this.creator = creator;
        this.creatorName = creatorName;
    }

    /** Whether this is the enemy-contact marker, which is always rendered red. */
    public boolean isEnemyMark() {
        return symbol == Symbol.ENEMY;
    }

    // ------------------------------------------------------------ network

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeByte(shape.ordinal());
        buf.writeByte(symbol.ordinal());
        buf.writeUtf(dimension, 128);
        buf.writeVarInt(xs.length);
        for (double x : xs) {
            buf.writeDouble(x);
        }
        for (double z : zs) {
            buf.writeDouble(z);
        }
        buf.writeDouble(radius);
        buf.writeUtf(label, 64);
        buf.writeUUID(creator);
        buf.writeUtf(creatorName, 64);
    }

    public static TacAnnotation decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        ShapeType shape = ShapeType.values()[Math.floorMod(buf.readByte(), ShapeType.values().length)];
        Symbol symbol = Symbol.byId(buf.readByte());
        String dim = buf.readUtf(128);
        int n = buf.readVarInt();
        if (n < 0 || n > 512) {
            throw new IllegalArgumentException("bad vertex count " + n);
        }
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = buf.readDouble();
        }
        for (int i = 0; i < n; i++) {
            zs[i] = buf.readDouble();
        }
        double radius = buf.readDouble();
        if (!Double.isFinite(radius) || radius < 0.0D || radius > 1000000.0D) {
            radius = 0.0D;
        }
        String label = buf.readUtf(64);
        UUID creator = buf.readUUID();
        String creatorName = buf.readUtf(64);
        return new TacAnnotation(id, shape, symbol, dim, xs, zs, radius, label, creator, creatorName);
    }

    @Override
    public String toString() {
        return "TacAnnotation{" + shape + "/" + symbol + " " + label
                + " @" + dimension + " " + Arrays.toString(xs) + "}";
    }
}
