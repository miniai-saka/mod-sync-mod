package io.github.miniai_saka.modsync.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

/** Declared Fabric metadata, not a guess based on a mod's name or entrypoints. */
public record ModDetails(String id, String name, String version, Side environment, List<String> provides) {
    public enum Side { CLIENT, SERVER, BOTH, UNKNOWN }
    public static final ModDetails UNKNOWN = new ModDetails("", "", "", Side.UNKNOWN, List.of());
    public ModDetails {
        id = Objects.requireNonNullElse(id, "");
        name = Objects.requireNonNullElse(name, "");
        version = Objects.requireNonNullElse(version, "");
        environment = Objects.requireNonNullElse(environment, Side.UNKNOWN);
        provides = provides == null ? List.of() : List.copyOf(provides);
    }
    public Set<String> ids() {
        Set<String> result = new HashSet<>(provides);
        if (!id.isEmpty()) result.add(id);
        return result;
    }
    public String historyKey(String file) { return id.isEmpty() ? "file:" + file.toLowerCase(Locale.ROOT) : "mod:" + id; }
    public boolean serverOnly() { return environment == Side.SERVER; }
    public boolean conflicts(ModDetails other) { return !Collections.disjoint(ids(), other.ids()); }
    public void validate() throws IOException {
        if (id.length() > 64 || name.length() > 256 || version.length() > 128 || provides.size() > 128
                || (!id.isEmpty() && !id.matches("[a-z][a-z0-9_-]{1,63}")))
            throw new IOException("Invalid Fabric mod metadata");
        for (String provided : provides) if (provided == null || !provided.matches("[a-z][a-z0-9_-]{1,63}"))
            throw new IOException("Invalid provided mod ID");
    }
    public static ModDetails read(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) return UNKNOWN;
            try (var input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) return UNKNOWN;
                JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                String environment = string(object, "environment", "*");
                if (!object.has("environment") && object.has("side")) environment = string(object, "side", "*");
                Side side = switch (environment) {
                    case "client" -> Side.CLIENT;
                    case "server" -> Side.SERVER;
                    case "*", "universal" -> Side.BOTH;
                    default -> Side.UNKNOWN;
                };
                List<String> provides = new ArrayList<>();
                if (object.has("provides")) for (JsonElement value : object.getAsJsonArray("provides")) {
                    if (provides.size() >= 128) return UNKNOWN;
                    provides.add(value.getAsString());
                }
                ModDetails result = new ModDetails(string(object, "id", ""), string(object, "name", ""),
                        string(object, "version", ""), side, provides);
                result.validate();
                return result;
            } catch (RuntimeException | IOException e) { return UNKNOWN; }
        } catch (ZipException e) { return UNKNOWN; }
    }
    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
    public static Map<String, ModDetails> scan(Path mods) throws IOException {
        Map<String, ModDetails> result = new TreeMap<>();
        for (var entry : Inventory.paths(mods).entrySet()) result.put(entry.getKey(), read(entry.getValue()));
        return result;
    }
}
