package io.github.miniai_saka.modsync.core;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

public final class PackValidation {
    private PackValidation() {}
    public static void requireSyncMod(Path game, Path stage, SyncPlan plan) throws IOException {
        Set<String> downloaded = new HashSet<>(); plan.downloads().forEach(e -> downloaded.add(e.name()));
        boolean found = false;
        for (String name : plan.expectedInventory().keySet()) {
            Path file = downloaded.contains(name) ? SafePaths.file(stage.resolve("downloads"), name) : SafePaths.file(game.resolve("mods"), name);
            ModDetails details = ModDetails.read(file);
            if (downloaded.contains(name) && details.id().isEmpty()) throw new IOException("Fabric Modではありません: " + name);
            // An unchecked library/Forge jar can stay untouched even though Fabric ignores it.
            if ("fabric_mod_sync".equals(details.id())) found = true;
        }
        if (!found) throw new IOException("選択した構成にはFabric Mod Syncが残りません。現在の同期Modを残すか、新版の導入を選択してください。");
    }
}
