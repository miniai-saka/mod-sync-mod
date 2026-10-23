package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Runs only after the game exits. A durable journal permits interrupted-update recovery. */
public final class Transaction {
    private Transaction() {}
    public static final class Journal {
        public String state = "PREPARED";
        public List<String> originals = new ArrayList<>();
        public List<String> installs = new ArrayList<>();
    }
    public static Path checkedStage(Path game, Path stage) throws IOException {
        Path expected = game.toAbsolutePath().normalize().resolve(".modsync/transactions");
        Path normalized = stage.toAbsolutePath().normalize();
        if (!expected.equals(normalized.getParent()) || !normalized.getFileName().toString().matches("[0-9a-f-]{36}"))
            throw new IOException("Invalid transaction directory");
        SafePaths.noLinks(normalized); return normalized;
    }
    public static void apply(Path game, Path inputStage) throws IOException {
        Path root = game.toAbsolutePath().normalize(), stage = checkedStage(root, inputStage);
        SafePaths.noLinks(root.resolve("mods"));
        Files.createDirectories(root.resolve("mods"));
        Files.createDirectories(root.resolve(".modsync"));
        try (FileChannel channel = FileChannel.open(root.resolve(".modsync/update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            if (ignored == null) throw new IOException("Another synchronization is running");
            recoverAll(root);
            SyncPlan plan = Json.read(stage.resolve("plan.json"), SyncPlan.class);
            plan.validate();
            if (!plan.changed()) return;
            if (!Inventory.scan(root.resolve("mods")).equals(plan.before())) throw new IOException("Local mods changed after approval");
            for (var file : plan.downloads()) Hashes.verify(SafePaths.file(stage.resolve("downloads"), file.name()), file);
            Path backup = stage.resolve("backup");
            Files.createDirectory(backup);
            Journal journal = new Journal();
            Set<String> replaced = new TreeSet<>(plan.removals());
            plan.downloads().forEach(e -> { if (plan.before().containsKey(e.name())) replaced.add(e.name()); });
            journal.originals.addAll(replaced);
            plan.downloads().forEach(e -> journal.installs.add(e.name()));
            Json.write(stage.resolve("journal.json"), journal);
            try {
                for (String name : journal.originals) Json.move(SafePaths.file(root.resolve("mods"), name), SafePaths.file(backup, name));
                for (var entry : plan.downloads()) {
                    // Copy, rather than move, so a failed update can be retried without downloading again.
                    Path incoming = stage.resolve("incoming.jar");
                    Files.copy(SafePaths.file(stage.resolve("downloads"), entry.name()), incoming, StandardCopyOption.REPLACE_EXISTING);
                    Json.move(incoming, SafePaths.file(root.resolve("mods"), entry.name()));
                }
                verifyTarget(root, plan);
                journal.state = "COMMITTED";
                Json.write(stage.resolve("journal.json"), journal);
                Json.write(root.resolve(".modsync/last-result.json"), Map.of("success", true, "revision", plan.manifest().revision(), "backup", stage.toString()));
            } catch (Exception error) {
                try { rollback(root, stage, journal, plan); }
                catch (Exception rollbackError) { error.addSuppressed(rollbackError); }
                throw new IOException("Update failed; original mods were restored when possible. See transaction backup.", error);
            }
        } catch (OverlappingFileLockException e) { throw new IOException("Another synchronization is running", e); }
    }
    public static void verifyExact(Path game, Manifest manifest) throws IOException {
        Map<String, Hashes.Value> expected = new TreeMap<>();
        for (var e : manifest.files()) expected.put(e.name(), e.hash());
        if (!Inventory.scan(game.resolve("mods")).equals(expected)) throw new IOException("Final complete mod inventory does not match server");
    }
    public static void verifyTarget(Path game, SyncPlan plan) throws IOException {
        if (!Inventory.scan(game.resolve("mods")).equals(plan.expectedInventory()))
            throw new IOException("Final mod inventory does not match the approved selection");
    }
    public static void recoverAll(Path game) throws IOException {
        Path transactions = game.resolve(".modsync/transactions");
        if (!Files.exists(transactions)) return;
        try (var paths = Files.list(transactions)) {
            for (Path stage : paths.sorted().toList()) {
                checkedStage(game, stage);
                if (!Files.exists(stage.resolve("journal.json"))) continue;
                Journal j = Json.read(stage.resolve("journal.json"), Journal.class);
                if ("PREPARED".equals(j.state)) rollback(game, stage, j, Json.read(stage.resolve("plan.json"), SyncPlan.class));
            }
        }
    }
    private static void rollback(Path game, Path stage, Journal j, SyncPlan plan) throws IOException {
        plan.validate();
        if (j.originals == null || j.installs == null) throw new IOException("Corrupt recovery journal");
        Path backup = stage.resolve("backup"); SafePaths.noLinks(backup);
        // Validate all backups before changing any current file.
        for (String name : j.originals) {
            Path file = SafePaths.file(backup, name);
            if (Files.exists(file) && !Hashes.of(file).equals(plan.before().get(name))) throw new IOException("Backup verification failed: " + name);
        }
        for (String name : j.installs) {
            SafePaths.fileName(name);
            // If the original was not moved yet, keep it in place.
            if (!plan.before().containsKey(name) || Files.exists(SafePaths.file(backup, name)))
                Files.deleteIfExists(SafePaths.file(game.resolve("mods"), name));
        }
        for (String name : j.originals) {
            Path file = SafePaths.file(backup, name);
            if (Files.exists(file)) Files.copy(file, SafePaths.file(game.resolve("mods"), name), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Inventory.scan(game.resolve("mods")).equals(plan.before())) throw new IOException("Recovery verification failed; retain backups");
        j.state = "ROLLED_BACK"; Json.write(stage.resolve("journal.json"), j);
    }
}
