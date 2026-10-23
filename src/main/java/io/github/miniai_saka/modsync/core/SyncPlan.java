package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.util.*;

/** The server manifest stays intact; target is the complete inventory approved by this client. */
public record SyncPlan(Manifest manifest, Map<String, Hashes.Value> before, List<Manifest.Entry> downloads,
                       List<String> removals, Map<String, Hashes.Value> target) {
    public SyncPlan(Manifest manifest, Map<String, Hashes.Value> before, List<Manifest.Entry> downloads, List<String> removals) {
        this(manifest, before, downloads, removals, null);
    }
    public static SyncPlan create(Manifest manifest, Map<String, Hashes.Value> local) throws IOException {
        manifest.validate();
        List<Manifest.Entry> downloads = new ArrayList<>();
        Set<String> required = new HashSet<>();
        if (manifest.enabled()) for (var file : manifest.files()) {
            required.add(file.name());
            if (!file.hash().equals(local.get(file.name()))) downloads.add(file);
        }
        List<String> remove = manifest.enabled() ? local.keySet().stream().filter(n -> !required.contains(n)).sorted().toList() : List.of();
        return new SyncPlan(manifest, new TreeMap<>(local), List.copyOf(downloads), remove, null);
    }
    public SyncPlan select(Set<String> install, Set<String> retire) throws IOException {
        List<Manifest.Entry> selected = downloads.stream().filter(e -> install.contains(e.name())).toList();
        List<String> removed = retire.stream().sorted().toList();
        Map<String, Hashes.Value> after = new TreeMap<>(before);
        removed.forEach(after::remove);
        selected.forEach(e -> after.put(e.name(), e.hash()));
        SyncPlan result = new SyncPlan(manifest, new TreeMap<>(before), selected, removed, after);
        result.validate();
        return result;
    }
    public Map<String, Hashes.Value> expectedInventory() {
        if (target != null) return Collections.unmodifiableMap(target);
        if (!manifest.enabled()) return Collections.unmodifiableMap(before);
        Map<String, Hashes.Value> result = new TreeMap<>();
        manifest.files().forEach(e -> result.put(e.name(), e.hash()));
        return result;
    }
    public boolean changed() { return !downloads.isEmpty() || !removals.isEmpty(); }
    public long downloadBytes() { return downloads.stream().mapToLong(Manifest.Entry::size).sum(); }
    public void validate() throws IOException {
        if (before == null || downloads == null || removals == null || manifest == null) throw new IOException("Invalid sync plan");
        for (String n : before.keySet()) SafePaths.fileName(n);
        manifest.validate();
        if (target == null) {
            // Preserve recovery of complete-sync transactions written by versions 1.0 and 1.1.
            SyncPlan expected = create(manifest, before);
            if (!expected.equals(this)) throw new IOException("Sync plan does not match manifest");
            return;
        }
        if (!manifest.enabled() && changed()) throw new IOException("Disabled server cannot request changes");
        Map<String, Manifest.Entry> offered = new HashMap<>();
        manifest.files().forEach(e -> offered.put(e.name(), e));
        Set<String> installed = new HashSet<>(), retired = new HashSet<>();
        Map<String, Hashes.Value> after = new TreeMap<>(before);
        for (String name : removals) {
            SafePaths.fileName(name);
            if (!before.containsKey(name) || !retired.add(name)) throw new IOException("Invalid retirement: " + name);
            after.remove(name);
        }
        for (Manifest.Entry entry : downloads) {
            if (entry == null || !entry.equals(offered.get(entry.name())) || !installed.add(entry.name()))
                throw new IOException("Download is not offered by this server");
            for (String localName : before.keySet()) if (localName.equalsIgnoreCase(entry.name()) && !retired.contains(localName))
                throw new IOException("Replacing a file requires its backup: " + localName);
            after.put(entry.name(), entry.hash());
        }
        Set<String> folded = new HashSet<>();
        for (String name : after.keySet()) if (!folded.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Case-colliding target files");
        if (!after.equals(target)) throw new IOException("Target inventory does not match the approved operations");
    }
}
