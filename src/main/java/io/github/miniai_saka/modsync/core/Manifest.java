package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record Manifest(int protocol, boolean enabled, String minecraft, String loader, String revision, List<Entry> files) {
    public static final int PROTOCOL = 1;
    public record Entry(String name, long size, String md5, String sha256) {
        public Hashes.Value hash() { return new Hashes.Value(size, md5, sha256); }
        public static Entry of(String name, Hashes.Value h) { return new Entry(name, h.size(), h.md5(), h.sha256()); }
    }
    public static Manifest create(boolean enabled, String minecraft, String loader, List<Entry> files) {
        List<Entry> sorted = files.stream().sorted(Comparator.comparing(Entry::name)).toList();
        return new Manifest(PROTOCOL, enabled, minecraft, loader, revision(enabled, minecraft, loader, sorted), sorted);
    }
    private static String revision(boolean enabled, String mc, String loader, List<Entry> files) {
        StringBuilder s = new StringBuilder(PROTOCOL + "\n" + enabled + "\n" + mc + "\n" + loader + "\n");
        files.forEach(e -> s.append(e.name()).append('\t').append(e.size()).append('\t').append(e.md5()).append('\t').append(e.sha256()).append('\n'));
        return Hashes.sha256(s.toString().getBytes(StandardCharsets.UTF_8));
    }
    public void validate() throws IOException {
        if (protocol != PROTOCOL || !"1.20.1".equals(minecraft) || loader == null || files == null || files.size() > 4096)
            throw new IOException("Unsupported server manifest (Minecraft 1.20.1 / protocol 1 required)");
        Set<String> names = new HashSet<>(); long total = 0;
        for (Entry e : files) {
            if (e == null) throw new IOException("Null file entry");
            SafePaths.fileName(e.name());
            if (!names.add(e.name().toLowerCase(Locale.ROOT)) || e.size() < 1 || e.size() > 2L * 1024 * 1024 * 1024
                    || e.md5() == null || !e.md5().matches("[0-9a-f]{32}")
                    || e.sha256() == null || !e.sha256().matches("[0-9a-f]{64}"))
                throw new IOException("Invalid/duplicate file entry: " + e.name());
            total += e.size();
        }
        if (total > 32L * 1024 * 1024 * 1024) throw new IOException("Pack exceeds 32 GiB");
        List<Entry> sorted = files.stream().sorted(Comparator.comparing(Entry::name)).toList();
        if (!Objects.equals(revision, revision(enabled, minecraft, loader, sorted))) throw new IOException("Manifest revision mismatch");
        if (enabled && files.isEmpty()) throw new IOException("Empty enabled pack refused; disable sync explicitly instead");
    }
}
