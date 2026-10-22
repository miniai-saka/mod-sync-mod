package io.github.miniai_saka.modsync.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;

class SyncIntegrationTest {
    @TempDir Path root;
    Path server, game;
    @BeforeEach void directories() throws IOException {
        server = Files.createDirectories(root.resolve("server"));
        game = Files.createDirectories(root.resolve("client"));
        Files.createDirectories(server.resolve("mods")); Files.createDirectories(game.resolve("mods"));
        mod(server, "sync.jar", "fabric_mod_sync", "bootstrap");
        Files.copy(server.resolve("mods/sync.jar"), game.resolve("mods/sync.jar"));
        ServerConfig c = new ServerConfig(); c.bindAddress = "127.0.0.1"; c.port = 0;
        Json.write(server.resolve("config/fabric-mod-sync-server.json"), c);
    }
    static Path mod(Path game, String name, String id, String content) throws IOException {
        Path path = game.resolve("mods").resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            JarEntry meta = new JarEntry("fabric.mod.json"); meta.setTime(0); out.putNextEntry(meta);
            out.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"environment\":\"*\"}").getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            JarEntry data = new JarEntry("data.txt"); data.setTime(0); out.putNextEntry(data); out.write(content.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        return path;
    }
    PackServer start() throws IOException { PackServer s = new PackServer(server, "1.20.1", "0.19.5"); s.start(); return s; }
    SyncTransport transport(PackServer service) throws IOException {
        ClientConfig c = new ClientConfig(); c.server = "127.0.0.1:" + service.port(); return new SyncTransport(c);
    }
    SyncPlan plan(SyncTransport t) throws Exception { return SyncPlan.create(t.manifest(), Inventory.scan(game.resolve("mods"))); }
    @Test void noDifferenceDownloadsNothing() throws Exception {
        try (PackServer service = start()) { SyncPlan p = plan(transport(service)); assertFalse(p.changed()); assertEquals(0, p.downloadBytes()); }
    }
    @Test void deltaTransferMd5AndExactReplacement() throws Exception {
        mod(server, "example-2.jar", "example", "version 2");
        mod(game, "example-1.jar", "example", "version 1");
        mod(game, "extra.jar", "extra", "client-only");
        try (PackServer service = start()) {
            SyncTransport t = transport(service); SyncPlan p = plan(t);
            assertEquals(List.of("example-2.jar"), p.downloads().stream().map(Manifest.Entry::name).toList());
            assertEquals(List.of("example-1.jar", "extra.jar"), p.removals());
            List<Long> values = new ArrayList<>();
            Path stage = t.stage(game, p, (f, i, n, d, total) -> values.add(d), new AtomicBoolean());
            try (var downloaded = Files.list(stage.resolve("downloads"))) { assertEquals(1, downloaded.count()); }
            assertEquals(p.downloadBytes(), values.get(values.size() - 1));
            Transaction.apply(game, stage);
            Transaction.verifyExact(game, p.manifest());
            assertTrue(Files.exists(stage.resolve("backup/example-1.jar")));
            assertTrue(Files.exists(stage.resolve("backup/extra.jar")));
            assertFalse(plan(t).changed());
        }
    }
    @Test void sameNameDifferentVersionReplacesAndBacksUp() throws Exception {
        mod(server, "example.jar", "example", "new"); mod(game, "example.jar", "example", "old");
        var original = Inventory.scan(game.resolve("mods"));
        try (PackServer service = start()) {
            SyncTransport t = transport(service); SyncPlan p = plan(t);
            assertEquals(1, p.downloads().size()); assertTrue(p.removals().isEmpty());
            Path stage = t.stage(game, p, (a,b,c,d,e) -> {}, new AtomicBoolean()); Transaction.apply(game, stage);
            assertEquals(original.get("example.jar"), Hashes.of(stage.resolve("backup/example.jar")));
            Transaction.verifyExact(game, p.manifest());
        }
    }
    @Test void configAutomaticallyListsFilesAndRespectsSelections() throws Exception {
        mod(server, "optional.jar", "optional", "data");
        try (PackServer service = start()) {
            Path cfg = server.resolve("config/fabric-mod-sync-server.json");
            ServerConfig c = ServerConfig.load(cfg);
            assertEquals(Set.of("optional.jar", "sync.jar"), c.files.keySet());
            c.syncAllFiles = false; c.files.put("optional.jar", false); Json.write(cfg, c);
            assertEquals(List.of("sync.jar"), service.refresh().files().stream().map(Manifest.Entry::name).toList());
            c.syncSelectedFiles = false; Json.write(cfg, c); assertFalse(service.refresh().enabled());
            c.syncAllFiles = true; Json.write(cfg, c); assertEquals(2, service.refresh().files().size());
        }
    }
    @Test void disabledAndEmptySelectionsDoNotDeleteMods() throws Exception {
        ServerConfig c = ServerConfig.load(server.resolve("config/fabric-mod-sync-server.json"));
        c.syncAllFiles = false; c.files.put("sync.jar", false); Json.write(server.resolve("config/fabric-mod-sync-server.json"), c);
        try (PackServer service = start()) { assertFalse(plan(transport(service)).changed()); assertFalse(service.refresh().enabled()); }
    }
    @Test void tokenRequiredAndValidTokenWorks() throws Exception {
        ServerConfig c = ServerConfig.load(server.resolve("config/fabric-mod-sync-server.json")); c.accessToken = "test-secret";
        Json.write(server.resolve("config/fabric-mod-sync-server.json"), c);
        try (PackServer service = start()) {
            assertThrows(IOException.class, () -> transport(service).manifest());
            ClientConfig client = new ClientConfig(); client.server = "127.0.0.1:" + service.port(); client.accessToken = "test-secret";
            assertTrue(new SyncTransport(client).manifest().enabled());
        }
    }
    @Test void corruptedDownloadCannotModifyExistingFiles() throws Exception {
        mod(server, "new.jar", "example", "new");
        var original = Inventory.scan(game.resolve("mods"));
        try (PackServer service = start()) {
            SyncTransport t = transport(service); SyncPlan p = plan(t);
            Path stage = t.stage(game, p, (a,b,c,d,e) -> {}, new AtomicBoolean());
            Files.writeString(stage.resolve("downloads/new.jar"), "tampered");
            assertThrows(IOException.class, () -> Transaction.apply(game, stage));
            assertEquals(original, Inventory.scan(game.resolve("mods")));
        }
    }
    @Test void alteredMd5IsRejectedEvenWithCorrectSha256() throws Exception {
        Manifest.Entry real = Manifest.Entry.of("sync.jar", Hashes.of(server.resolve("mods/sync.jar")));
        Manifest.Entry wrong = new Manifest.Entry(real.name(), real.size(), "0".repeat(32), real.sha256());
        assertThrows(IOException.class, () -> Hashes.verify(server.resolve("mods/sync.jar"), wrong));
    }
    @Test void changedServerDuringTransferAborts() throws Exception {
        mod(server, "new.jar", "example", "new"); AtomicBoolean changed = new AtomicBoolean();
        var original = Inventory.scan(game.resolve("mods"));
        try (PackServer service = start()) {
            SyncTransport t = transport(service); SyncPlan p = plan(t);
            assertThrows(IOException.class, () -> t.stage(game, p, (f,i,n,d,total) -> {
                if (changed.compareAndSet(false, true)) try { mod(server, "arrived.jar", "arrived", "late"); } catch (IOException e) { throw new UncheckedIOException(e); }
            }, new AtomicBoolean()));
            assertEquals(original, Inventory.scan(game.resolve("mods")));
        }
    }
    @Test void cancelledDownloadLeavesModsUntouched() throws Exception {
        mod(server, "new.jar", "example", "new"); var original = Inventory.scan(game.resolve("mods"));
        try (PackServer service = start()) {
            SyncTransport t = transport(service);
            assertThrows(InterruptedIOException.class, () -> t.stage(game, plan(t), (a,b,c,d,e) -> {}, new AtomicBoolean(true)));
            assertEquals(original, Inventory.scan(game.resolve("mods")));
        }
    }
    @Test void localChangesAfterDownloadPreventInstallation() throws Exception {
        mod(server, "new.jar", "example", "new");
        try (PackServer service = start()) {
            SyncTransport t = transport(service); Path stage = t.stage(game, plan(t), (a,b,c,d,e) -> {}, new AtomicBoolean());
            mod(game, "user-added.jar", "useradded", "added"); var changed = Inventory.scan(game.resolve("mods"));
            assertThrows(IOException.class, () -> Transaction.apply(game, stage));
            assertEquals(changed, Inventory.scan(game.resolve("mods")));
        }
    }
    @Test void interruptedReplacementRecoversFromJournal() throws Exception {
        mod(server, "example.jar", "example", "new"); mod(game, "example.jar", "example", "old");
        mod(game, "extra.jar", "extra", "remove me"); var original = Inventory.scan(game.resolve("mods"));
        try (PackServer service = start()) {
            SyncTransport t = transport(service); SyncPlan p = plan(t); Path stage = t.stage(game, p, (a,b,c,d,e) -> {}, new AtomicBoolean());
            Files.createDirectory(stage.resolve("backup"));
            Transaction.Journal j = new Transaction.Journal(); j.originals = List.of("example.jar", "extra.jar"); j.installs = List.of("example.jar");
            Json.write(stage.resolve("journal.json"), j);
            Json.move(game.resolve("mods/example.jar"), stage.resolve("backup/example.jar"));
            Files.copy(stage.resolve("downloads/example.jar"), game.resolve("mods/example.jar"));
            Transaction.recoverAll(game);
            assertEquals(original, Inventory.scan(game.resolve("mods")));
            assertEquals("ROLLED_BACK", Json.read(stage.resolve("journal.json"), Transaction.Journal.class).state);
        }
    }
    @Test void traversalReservedNamesAndCaseDuplicatesRejected() throws Exception {
        for (String name : List.of("../bad.jar", "a/b.jar", "a\\b.jar", "C:bad.jar", "CON.jar", ".hidden.jar", "foo.jar:stream", "bad.exe"))
            assertThrows(IOException.class, () -> SafePaths.fileName(name));
        var h = Hashes.of(server.resolve("mods/sync.jar"));
        Manifest duplicate = Manifest.create(true, "1.20.1", "0.19.5", List.of(Manifest.Entry.of("A.jar", h), Manifest.Entry.of("a.jar", h)));
        assertThrows(IOException.class, duplicate::validate);
    }
    @Test void unrelatedFilesAreNotServed() throws Exception {
        try (PackServer service = start()) {
            String revision = service.refresh().revision();
            HttpClient client = HttpClient.newHttpClient();
            for (String file : List.of("missing.jar", "..%2Fserver.properties")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/modsync/v1/file/" + file)).header("X-ModSync-Revision", revision).build();
                assertNotEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }
    @Test void endpointValidation() throws Exception {
        assertEquals("http://localhost:25566/", ClientConfig.endpoint("localhost:25566").toString());
        assertEquals("https://example.com:443/", ClientConfig.endpoint("https://example.com:443").toString());
        assertEquals("http://[::1]:25566/", ClientConfig.endpoint("[::1]:25566").toString());
        for (String bad : List.of("file:///etc/passwd", "http://user:pass@host:80", "host:0", "host:99999", "https://host/path", "http://host?query"))
            assertThrows(IOException.class, () -> ClientConfig.endpoint(bad));
    }
    @Test void helperWaitsForGameThenInstallsAndActuallyRestarts() throws Exception {
        mod(server, "new.jar", "example", "new");
        try (PackServer service = start()) {
            SyncTransport t = transport(service); SyncPlan p = plan(t); Path stage = t.stage(game, p, (a,b,c,d,e) -> {}, new AtomicBoolean());
            String java = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
            String testClasses = Path.of(RestartProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            Process parent = new ProcessBuilder(java, "-cp", testClasses, RestartProbe.class.getName(), "wait").start();
            Process helper = null;
            try {
                assertEquals("WAITING", new BufferedReader(new InputStreamReader(parent.getInputStream())).readLine());
                Path marker = game.resolve("restart-proof.txt");
                List<String> command = List.of(java, "-cp", testClasses, RestartProbe.class.getName(), "mark", marker.toString());
                helper = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", System.getProperty("modsync.helper"), "update").redirectError(ProcessBuilder.Redirect.INHERIT).start();
                try (var writer = new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8)) {
                    Json.GSON.toJson(new HelperMain.Request(game.toString(), stage.toString(), parent.pid(), command, game.toString()), writer);
                }
                assertEquals("READY", new BufferedReader(new InputStreamReader(helper.getInputStream())).readLine());
                assertFalse(Files.exists(marker)); assertFalse(Files.exists(game.resolve("mods/new.jar")));
                parent.getOutputStream().close(); assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
                assertTrue(helper.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, helper.exitValue());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(50);
                assertEquals("restarted", Files.readString(marker)); Transaction.verifyExact(game, p.manifest());
            } finally { parent.destroyForcibly(); if (helper != null) helper.destroyForcibly(); }
        }
    }
    @Test void optionalPrelaunchAgentChecksBeforeMain() throws Exception {
        try (PackServer service = start()) {
            ClientConfig config = new ClientConfig(); config.enabled = true; config.server = "127.0.0.1:" + service.port();
            Json.write(game.resolve("config/fabric-mod-sync-client.json"), config);
            Path loader = root.resolve("fabric-loader-0.19.5.jar");
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(loader))) {}
            String java = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
            String cp = Path.of(RestartProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator + loader;
            Path marker = game.resolve("preflight-proof.txt");
            Process child = new ProcessBuilder(java, "-Djava.awt.headless=true", "-javaagent:" + System.getProperty("modsync.helper") + "=" + game,
                    "-cp", cp, RestartProbe.class.getName(), "preflight", marker.toString()).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("true", Files.readString(marker));
            } finally { child.destroyForcibly(); }
        }
    }
}
