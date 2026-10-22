package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.util.*;

public record SyncPlan(Manifest manifest, Map<String, Hashes.Value> before, List<Manifest.Entry> downloads, List<String> removals) {
    public static SyncPlan create(Manifest manifest, Map<String, Hashes.Value> local) throws IOException {
        manifest.validate();
        List<Manifest.Entry> downloads = new ArrayList<>();
        Set<String> required = new HashSet<>();
        if (manifest.enabled()) for (var file : manifest.files()) {
            required.add(file.name());
            if (!file.hash().equals(local.get(file.name()))) downloads.add(file);
        }
        List<String> remove = manifest.enabled() ? local.keySet().stream().filter(n -> !required.contains(n)).sorted().toList() : List.of();
        return new SyncPlan(manifest, new TreeMap<>(local), List.copyOf(downloads), remove);
    }
    public boolean changed() { return !downloads.isEmpty() || !removals.isEmpty(); }
    public long downloadBytes() { return downloads.stream().mapToLong(Manifest.Entry::size).sum(); }
    public void validate() throws IOException {
        if (before == null || downloads == null || removals == null || manifest == null) throw new IOException("Invalid sync plan");
        for (String n : before.keySet()) SafePaths.fileName(n);
        SyncPlan expected = create(manifest, before);
        if (!expected.equals(this)) throw new IOException("Sync plan does not match manifest");
    }
}
