package io.github.miniai_saka.modsync.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class SelectionTest {
    @TempDir Path root;
    private final Hashes.Value oldHash = new Hashes.Value(1, "a".repeat(32), "b".repeat(64));
    private final Hashes.Value newHash = new Hashes.Value(2, "c".repeat(32), "d".repeat(64));
    private ModDetails details(String id, String version, ModDetails.Side side) { return new ModDetails(id, id, version, side, List.of()); }
    private Manifest.Entry entry(String name, String id, ModDetails.Side side) {
        return new Manifest.Entry(name, newHash.size(), newHash.md5(), newHash.sha256(), details(id, "2", side), side != ModDetails.Side.SERVER);
    }
    private SyncSelection selection(String oldName, String newName, SelectionHistory.State remembered) throws Exception {
        Manifest manifest = Manifest.create(true, "1.20.1", "0.19.5", List.of(entry(newName, "example", ModDetails.Side.BOTH)));
        return new SyncSelection(SyncPlan.create(manifest, Map.of(oldName, oldHash)),
                Map.of(oldName, details("example", "1", ModDetails.Side.BOTH)), remembered);
    }
    private SyncSelection.Item item(SyncSelection model, SyncSelection.Action action, String name) {
        return model.items().stream().filter(i -> i.action() == action && i.file().equals(name)).findFirst().orElseThrow();
    }
    @Test void retainingOldVersionUnchecksNewVersionAndSelectingNewRestoresBackup() throws Exception {
        SyncSelection model = selection("example-1.jar", "example-2.jar", new SelectionHistory.State());
        var old = item(model, SyncSelection.Action.RETIRE, "example-1.jar");
        var incoming = item(model, SyncSelection.Action.INSTALL, "example-2.jar");
        assertTrue(old.selected()); assertTrue(incoming.selected());
        model.setSelected(old, false);
        assertFalse(incoming.selected()); assertFalse(model.plan().changed());
        model.setSelected(incoming, true);
        assertTrue(old.selected()); assertEquals(Set.of("example-2.jar"), model.plan().expectedInventory().keySet());
        model.setSelected(incoming, false);
        assertFalse(old.selected()); assertEquals(Set.of("example-1.jar"), model.plan().expectedInventory().keySet());
    }
    @Test void advancedModeAllowsDifferentFileNamesWithSameIdAndNormalModeRestoresGuard() throws Exception {
        SyncSelection model = selection("example-1.jar", "example-2.jar", new SelectionHistory.State());
        model.setAdvanced(true);
        model.setSelected(item(model, SyncSelection.Action.RETIRE, "example-1.jar"), false);
        assertTrue(item(model, SyncSelection.Action.INSTALL, "example-2.jar").selected());
        assertEquals(2, model.plan().expectedInventory().size());
        model.setAdvanced(false);
        assertFalse(item(model, SyncSelection.Action.INSTALL, "example-2.jar").selected());
        assertFalse(model.plan().changed());
    }
    @Test void sameFileBackupRemainsMandatoryEvenInAdvancedMode() throws Exception {
        SyncSelection model = selection("example.jar", "example.jar", new SelectionHistory.State());
        model.setAdvanced(true);
        model.setSelected(item(model, SyncSelection.Action.RETIRE, "example.jar"), false);
        assertFalse(item(model, SyncSelection.Action.INSTALL, "example.jar").selected());
        model.setSelected(item(model, SyncSelection.Action.INSTALL, "example.jar"), true);
        assertTrue(item(model, SyncSelection.Action.RETIRE, "example.jar").selected());
        model.plan().validate();
    }
    @Test void bulkActionsAndServerOnlyDefaultAllowExplicitOverride() throws Exception {
        var serverOnly = entry("server.jar", "server_only", ModDetails.Side.SERVER);
        var normal = entry("client.jar", "client_mod", ModDetails.Side.CLIENT);
        SyncSelection model = new SyncSelection(SyncPlan.create(Manifest.create(true, "1.20.1", "0.19.5", List.of(serverOnly, normal)), Map.of()),
                Map.of(), new SelectionHistory.State());
        assertFalse(item(model, SyncSelection.Action.INSTALL, "server.jar").selected());
        assertTrue(item(model, SyncSelection.Action.INSTALL, "client.jar").selected());
        model.selectAll(true); assertEquals(2, model.plan().downloads().size());
        model.selectAll(false); assertFalse(model.plan().changed());
        model.setSelected(item(model, SyncSelection.Action.INSTALL, "server.jar"), true);
        assertEquals(List.of("server.jar"), model.plan().downloads().stream().map(Manifest.Entry::name).toList());
    }
    @Test void skippedModIsRememberedAcrossFileVersionChangesAndEndpointsStaySeparate() throws Exception {
        ClientConfig config = new ClientConfig(); config.server = "EXAMPLE.com:25565";
        SelectionHistory history = new SelectionHistory();
        var remembered = history.server(config);
        var first = selection("example-1.jar", "example-2.jar", remembered);
        first.setSelected(item(first, SyncSelection.Action.RETIRE, "example-1.jar"), false);
        first.remember(remembered);
        Path path = SelectionHistory.path(root); history.save(path);
        SelectionHistory restored = SelectionHistory.load(path);
        config.server = "example.com:25565";
        var next = selection("example-1.jar", "example-3.jar", restored.server(config));
        assertFalse(next.plan().changed());
        config.server = "example.com:25566";
        assertTrue(selection("example-1.jar", "example-3.jar", restored.server(config)).plan().changed());
    }
    @Test void duplicateModIdsAndProvidedIdsCannotBothBeInstalledNormally() throws Exception {
        var one = entry("one.jar", "example", ModDetails.Side.BOTH);
        var two = new Manifest.Entry("two.jar", 2, newHash.md5(), newHash.sha256(),
                new ModDetails("replacement", "", "2", ModDetails.Side.BOTH, List.of("example")), true);
        var model = new SyncSelection(SyncPlan.create(Manifest.create(true, "1.20.1", "0.19.5", List.of(one, two)), Map.of()), Map.of(), new SelectionHistory.State());
        assertEquals(1, model.plan().downloads().size());
        model.setSelected(item(model, SyncSelection.Action.INSTALL, "two.jar"), true);
        assertFalse(item(model, SyncSelection.Action.INSTALL, "one.jar").selected());
        model.setAdvanced(true); model.selectAll(true);
        assertEquals(2, model.plan().downloads().size());
    }
    @Test void partialTransferKeepsUncheckedLocalModAndDoesNotReadUnselectedMissingJar() throws Exception {
        Path server = root.resolve("server"), game = root.resolve("game");
        Files.createDirectories(server.resolve("mods")); Files.createDirectories(game.resolve("mods"));
        Path sync = SyncIntegrationTest.mod(server, "sync.jar", "fabric_mod_sync", "sync");
        Files.copy(sync, game.resolve("mods/sync.jar"));
        SyncIntegrationTest.mod(server, "selected.jar", "selected", "new");
        SyncIntegrationTest.mod(server, "skip.jar", "skipped", "skip");
        SyncIntegrationTest.mod(game, "keep.jar", "keep_local", "keep");
        try (var ignored = new java.util.jar.JarOutputStream(Files.newOutputStream(game.resolve("mods/keep-library.jar")))) {}
        try (PackServer service = new PackServer(server, "1.20.1", "0.19.5", new java.net.InetSocketAddress("127.0.0.1", 0))) {
            service.start();
            ClientConfig config = new ClientConfig(); config.server = "127.0.0.1:" + service.port();
            SyncTransport transport = new SyncTransport(config);
            SyncPlan base = SyncPlan.create(transport.manifest(), Inventory.scan(game.resolve("mods")));
            var model = new SyncSelection(base, ModDetails.scan(game.resolve("mods")), new SelectionHistory.State());
            model.setSelected(item(model, SyncSelection.Action.INSTALL, "skip.jar"), false);
            model.setSelected(item(model, SyncSelection.Action.RETIRE, "keep.jar"), false);
            model.setSelected(item(model, SyncSelection.Action.RETIRE, "keep-library.jar"), false);
            SyncPlan selected = model.plan();
            List<String> transfers = new ArrayList<>();
            Path stage = transport.stage(game, selected, (name, a, b, c, d) -> { if (name.endsWith(".jar") && !transfers.contains(name)) transfers.add(name); }, new AtomicBoolean());
            Transaction.apply(game, stage); Transaction.verifyTarget(game, selected);
            assertEquals(List.of("selected.jar"), transfers);
            assertEquals(Set.of("sync.jar", "selected.jar", "keep.jar", "keep-library.jar"), Inventory.scan(game.resolve("mods")).keySet());
            assertFalse(Files.exists(game.resolve("mods/skip.jar")));
        }
    }
    @Test void forgedTargetCannotRemoveOrReplaceUnapprovedFiles() throws Exception {
        SyncSelection model = selection("old.jar", "new.jar", new SelectionHistory.State());
        SyncPlan selected = model.plan();
        SyncPlan forged = new SyncPlan(selected.manifest(), selected.before(), selected.downloads(), selected.removals(), Map.of("unapproved.jar", newHash));
        assertThrows(java.io.IOException.class, forged::validate);
    }
    @Test void oldPlanJsonStillValidatesForRollbackRecovery() throws Exception {
        Manifest manifest = Manifest.create(true, "1.20.1", "0.19.5", List.of(entry("same.jar", "example", ModDetails.Side.BOTH)));
        SyncPlan legacy = SyncPlan.create(manifest, Map.of("same.jar", oldHash));
        var json = Json.GSON.toJsonTree(legacy).getAsJsonObject(); json.remove("target");
        SyncPlan restored = Json.GSON.fromJson(json, SyncPlan.class);
        restored.validate();
        assertEquals(Set.of("same.jar"), restored.expectedInventory().keySet());
    }
}
