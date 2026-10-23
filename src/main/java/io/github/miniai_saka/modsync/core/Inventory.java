package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Inventory {
    private Inventory() {}
    public static SortedMap<String, Hashes.Value> scan(Path mods) throws IOException {
        SortedMap<String, Hashes.Value> result = new TreeMap<>();
        for (var entry : paths(mods).entrySet()) result.put(entry.getKey(), Hashes.of(entry.getValue()));
        return result;
    }
    public static SortedMap<String, Path> paths(Path mods) throws IOException {
        SafePaths.noLinks(mods);
        SortedMap<String, Path> result = new TreeMap<>();
        if (!Files.exists(mods)) return result;
        Set<String> folded = new HashSet<>();
        try (var paths = Files.list(mods)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (var nested = Files.walk(path)) {
                        if (nested.anyMatch(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")))
                            throw new IOException("Nested mod jars are not supported; move jars into mods/: " + path.getFileName());
                    }
                    continue;
                }
                String name = path.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                SafePaths.file(mods, name);
                if (!folded.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Case-colliding mod files");
                result.put(name, path);
            }
        }
        return result;
    }
}
