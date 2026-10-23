package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** In-memory file hashes and metadata. A startup cache never re-hashes a jar during requests. */
final class ServerCatalog {
    record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) {
        static Stamp read(Path file) throws IOException {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Not a regular mod file");
            return new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }
    record Jar(Hashes.Value hash, ModDetails details, Stamp stamp) {}
    private Path source;
    private final SortedMap<String, Jar> jars = new TreeMap<>();
    private long computations;

    SortedMap<String, Jar> refresh(Path directory, SortedMap<String, Path> paths, String mode) throws IOException {
        boolean initial = source == null;
        if (source != null && !source.equals(directory)) {
            if ("startup".equals(mode)) throw new IOException("sourceDirectoryを変更した場合はサーバーを再起動するか、hashCacheModeをon_changeにしてください。");
            jars.clear(); initial = true;
        }
        source = directory;
        if (!initial && "startup".equals(mode)) return Collections.unmodifiableSortedMap(jars);
        jars.keySet().retainAll(paths.keySet());
        for (var entry : paths.entrySet()) {
            Stamp stamp = Stamp.read(entry.getValue());
            Jar previous = jars.get(entry.getKey());
            if (initial || "disabled".equals(mode) || previous == null || !previous.stamp.equals(stamp)) {
                Hashes.Value hash = hash(entry.getValue());
                ModDetails details = ModDetails.read(entry.getValue());
                if (!stamp.equals(Stamp.read(entry.getValue()))) throw new IOException("Mod changed while creating its cache: " + entry.getKey());
                jars.put(entry.getKey(), new Jar(hash, details, stamp));
            }
        }
        return Collections.unmodifiableSortedMap(jars);
    }
    Hashes.Value hash(Path file) throws IOException { computations++; return Hashes.of(file); }
    long computations() { return computations; }
    boolean unchanged(String name, Path file) {
        try {
            Jar cached = jars.get(name);
            return cached != null && cached.stamp.equals(Stamp.read(file));
        } catch (IOException e) { return false; }
    }
}
