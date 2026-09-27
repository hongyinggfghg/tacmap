package dev.tacmap.xaerotacmap.client.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;

import dev.tacmap.xaerotacmap.annotation.TacAnnotation;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Serialization of annotation lists to/from {@code .tacmap} JSON files under
 * {@code config/tacmap_exports/}. The format is deliberately flat and
 * version-tagged so files stay hand-editable.
 */
public final class MarkerIO {

    private static final String FORMAT_VERSION = "1";

    private MarkerIO() {
    }

    public static Path exportDir() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("tacmap_exports");
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
            }
        }
        return dir;
    }

    public static List<Path> listFiles() {
        Path dir = exportDir();
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".tacmap"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    public static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) {
            name = "tacmap-export";
        }
        if (name.length() > 48) {
            name = name.substring(0, 48);
        }
        return name;
    }

    public static void write(List<TacAnnotation> markers, Path file,
                             String exportedBy) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("exportedBy", exportedBy);
        root.addProperty("exportedAt", java.time.Instant.now().toString());
        JsonArray arr = new JsonArray();
        for (TacAnnotation a : markers) {
            arr.add(toJson(a));
        }
        root.add("markers", arr);
        Files.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns (markers, formatVersion) or throws on malformed input. */
    public static Pair<List<TacAnnotation>, String> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        String format = root.has("format") ? root.get("format").getAsString() : "1";
        List<TacAnnotation> out = new ArrayList<>();
        if (root.has("markers") && root.get("markers").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("markers")) {
                try {
                    out.add(fromJson(el.getAsJsonObject()));
                } catch (RuntimeException ignored) {
                    // skip malformed entries, keep the rest
                }
            }
        }
        return Pair.of(out, format);
    }

    public static JsonObject toJson(TacAnnotation a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id.toString());
        o.addProperty("shape", a.shape.name());
        o.addProperty("symbol", a.symbol.name());
        o.addProperty("dimension", a.dimension);
        JsonArray xs = new JsonArray();
        for (double v : a.xs) {
            xs.add(v);
        }
        o.add("xs", xs);
        JsonArray zs = new JsonArray();
        for (double v : a.zs) {
            zs.add(v);
        }
        o.add("zs", zs);
        o.addProperty("radius", a.radius);
        o.addProperty("label", a.label);
        o.addProperty("creator", a.creator.toString());
        o.addProperty("creatorName", a.creatorName);
        return o;
    }

    public static TacAnnotation fromJson(JsonObject o) {
        TacAnnotation.ShapeType shape = TacAnnotation.ShapeType.valueOf(
                o.get("shape").getAsString());
        TacAnnotation.Symbol symbol = TacAnnotation.Symbol.valueOf(
                o.get("symbol").getAsString());
        JsonArray xs = o.getAsJsonArray("xs");
        JsonArray zs = o.getAsJsonArray("zs");
        double[] xv = new double[xs.size()];
        double[] zv = new double[zs.size()];
        for (int i = 0; i < xv.length && i < zs.size(); i++) {
            xv[i] = xs.get(i).getAsDouble();
            zv[i] = zs.get(i).getAsDouble();
        }
        return new TacAnnotation(
                UUID.fromString(o.get("id").getAsString()),
                shape,
                symbol,
                o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld",
                xv,
                zv,
                o.has("radius") ? o.get("radius").getAsDouble() : 0.0D,
                o.has("label") ? o.get("label").getAsString() : "",
                o.has("creator") ? UUID.fromString(o.get("creator").getAsString()) : new UUID(0, 0),
                o.has("creatorName") ? o.get("creatorName").getAsString() : "?");
    }
}
