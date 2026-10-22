package io.github.miniai_saka.modsync.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

public final class PackValidation {
    private PackValidation() {}
    public static void requireSyncMod(Path game, Path stage, SyncPlan plan) throws IOException {
        Set<String> downloaded = new HashSet<>(); plan.downloads().forEach(e -> downloaded.add(e.name()));
        boolean found = false;
        for (var e : plan.manifest().files()) {
            Path file = downloaded.contains(e.name()) ? SafePaths.file(stage.resolve("downloads"), e.name()) : SafePaths.file(game.resolve("mods"), e.name());
            try (JarFile jar = new JarFile(file.toFile())) {
                var entry = jar.getJarEntry("fabric.mod.json");
                if (entry == null) throw new IOException("Fabric Modではありません: " + e.name());
                try (var input = jar.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                    if (bytes.length > 1024 * 1024) throw new IOException("Oversized mod metadata");
                    var metadata = Json.GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                    if (metadata != null && metadata.has("id") && "fabric_mod_sync".equals(metadata.get("id").getAsString())) found = true;
                } catch (RuntimeException ex) { throw new IOException("Invalid mod metadata: " + e.name(), ex); }
            }
        }
        if (!found) throw new IOException("サーバーの配布対象にFabric Mod Sync自身がありません。同期Modも有効にしてください。");
    }
}
