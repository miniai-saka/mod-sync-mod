package io.github.miniai_saka.modsync.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.*;

class ServerAuditTest {
    @TempDir Path root;
    private Path configPath;
    private PackService service;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final InetAddress peer = InetAddress.getLoopbackAddress();
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(root.resolve("mods")); configPath = root.resolve("config/fabric-mod-sync-server.json");
        try (var jar = new JarOutputStream(Files.newOutputStream(root.resolve("mods/sync.jar")))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json")); jar.write("{\"schemaVersion\":1,\"id\":\"fabric_mod_sync\",\"version\":\"1\"}".getBytes()); jar.closeEntry();
        }
        service = new PackService(root, "1.20.1", "0.19.5", (level, text) -> logs.add(level + " " + text));
    }
    private ServerConfig config() throws IOException { return ServerConfig.load(configPath); }
    private SyncAudit.Response manifest() { return service.openRequest("GET", "/modsync/v1/manifest", null, null, peer, false); }
    private SyncAudit.Response file() throws IOException {
        return service.openRequest("GET", "/modsync/v1/file/sync.jar", service.refresh().revision(), null, peer, false);
    }
    private void send(SyncAudit.Response response) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); PackService.writeBody(response.reply(), body); response.finish(body.size(), true);
    }
    @Test void oldConfigsKeepCompatibleDefaultsAndPersistNewSettings() throws Exception {
        Files.writeString(configPath, "{\"syncAllFiles\":true}");
        ServerConfig old = config(); assertEquals("normal", old.logMode); assertTrue(old.logRejectedRequests);
        assertTrue(old.logSuccessfulRequests); assertTrue(old.logTransfers); assertTrue(old.allowedIps.isEmpty()); assertTrue(old.deniedIps.isEmpty());
        send(manifest()); assertEquals(1, logs.size()); assertTrue(logs.get(0).startsWith("INFO Mod sync success"));
    }
    @Test void deniedIpWinsBeforeTokenAndHashWorkAndCanChangeWhileRunning() throws Exception {
        ServerConfig c = config(); c.deniedIps = List.of(peer.getHostAddress()); c.allowedIps = List.of(peer.getHostAddress());
        c.hashCacheMode = "disabled"; c.accessToken = "private-token"; Json.write(configPath, c);
        var response = manifest(); assertEquals(403, response.reply().status()); response.finish(0, false);
        assertEquals(1, service.hashComputations()); assertTrue(logs.get(0).contains("reason=ip_denied")); assertFalse(logs.get(0).contains(c.accessToken));
        c.deniedIps = List.of(); c.accessToken = ""; Json.write(configPath, c);
        send(manifest()); assertTrue(logs.get(1).contains("success")); assertEquals(2, service.hashComputations());
    }
    @Test void missingAndUnlistedPeersCannotUseManifestOrFileAndAllowedPeerCan() throws Exception {
        ServerConfig c = config(); c.allowedIps = List.of("192.0.2.1"); Json.write(configPath, c);
        assertEquals(403, manifest().reply().status()); assertEquals(403, file().reply().status());
        assertEquals(403, service.prepare("GET", "/modsync/v1/manifest", null, null).status());
        c.allowedIps = List.of(peer.getHostAddress()); Json.write(configPath, c);
        assertEquals(200, manifest().reply().status()); assertEquals(200, file().reply().status());
    }
    @Test void offSuppressesRequestLogsWithoutDisablingAccessChecks() throws Exception {
        ServerConfig c = config(); c.logMode = "off"; Json.write(configPath, c); send(manifest()); send(file());
        c.deniedIps = List.of(peer.getHostAddress()); Json.write(configPath, c); manifest().finish(0, false);
        assertEquals(403, manifest().reply().status()); assertTrue(logs.isEmpty());
    }
    @Test void rejectionSuccessAndTransferSwitchesAreIndependentAndReloaded() throws Exception {
        ServerConfig c = config(); c.logSuccessfulRequests = false; c.logRejectedRequests = false; Json.write(configPath, c);
        send(manifest()); assertTrue(logs.isEmpty());
        var transfer = file(); send(transfer); assertEquals(1, logs.size()); assertTrue(logs.get(0).contains("bytes=" + transfer.reply().length()));
        logs.clear(); c.logTransfers = false; Json.write(configPath, c); send(file()); assertTrue(logs.isEmpty());
        c.logSuccessfulRequests = true; Json.write(configPath, c); send(file());
        assertEquals(1, logs.size()); assertFalse(logs.get(0).contains("bytes="));
        logs.clear(); c.deniedIps = List.of(peer.getHostAddress()); Json.write(configPath, c); manifest().finish(0, false); assertTrue(logs.isEmpty());
        c.logRejectedRequests = true; Json.write(configPath, c); manifest().finish(0, false);
        assertEquals(1, logs.size()); assertTrue(logs.get(0).startsWith("WARN Mod sync rejected"));
    }
    @Test void detailedModeAddsTimingAndCountsCompletedBytesOnce() throws Exception {
        ServerConfig c = config(); c.logMode = "detailed"; Json.write(configPath, c);
        var response = file(); assertTrue(logs.isEmpty(), "Preparing a response is not successful transmission");
        send(response); response.finish(0, false);
        assertEquals(1, logs.size()); assertTrue(logs.get(0).contains("elapsed_ms="));
        assertTrue(logs.get(0).contains("expected_bytes=" + response.reply().length()));
        logs.clear(); c.logMode = "normal"; Json.write(configPath, c); send(file()); assertFalse(logs.get(0).contains("elapsed_ms="));
    }
    @Test void partialAndFailedTransfersNeverProduceSuccessEvenIfAllBytesWereQueued() throws Exception {
        ServerConfig c = config(); c.logMode = "detailed"; Json.write(configPath, c);
        var first = file(); first.finish(17, false);
        assertTrue(logs.get(0).contains("aborted")); assertTrue(logs.get(0).contains("bytes=17 ")); assertFalse(logs.get(0).contains("success"));
        var second = file(); second.finish(second.reply().length(), false);
        assertTrue(logs.get(1).contains("aborted"));
        var third = file(); third.finish(0, true); assertTrue(logs.get(2).contains("aborted"));
    }
    @Test void unknownUrlsStaySilentAndNeverLogQueriesTokensOrControlCharacters() throws Exception {
        String token = "private-token-must-not-leak";
        var unknown = service.openRequest("GET\r\nforged", "/?token=" + token, null, "Bearer " + token, peer, false);
        assertTrue(unknown.reply().discarded()); unknown.finish(0, false);
        assertEquals(1, logs.size()); assertFalse(logs.get(0).contains(token)); assertFalse(logs.get(0).contains("\n"));
        assertTrue(logs.get(0).contains("path=<outside-sync-api>")); assertTrue(logs.get(0).contains("reason=outside_sync_api"));
    }
    @Test void malformedLiveRulesFailClosedWithoutReturningConfigContentsAndRecoverAfterRepair() throws Exception {
        Files.writeString(configPath, "{\"allowedIps\":[\"not-an-ip\"]}");
        var failed = manifest(); assertEquals(500, failed.reply().status()); failed.finish(0, false);
        assertTrue(logs.get(0).contains("invalid_config")); assertFalse(logs.get(0).contains("not-an-ip"));
        assertTrue(service.openRequest("GET", "/", null, null, peer, false).reply().discarded());
        Json.write(configPath, new ServerConfig()); assertEquals(200, manifest().reply().status());
    }
    @Test void invalidLogSettingsAndNullListsAreRejectedAndLoggingFailuresCannotBreakResponse() throws Exception {
        for (String bad : List.of("{\"logMode\":\"debug\"}", "{\"logMode\":null}", "{\"allowedIps\":null}", "{\"deniedIps\":[null]}")) {
            Files.writeString(configPath, bad); assertThrows(IOException.class, this::config);
        }
        Json.write(configPath, new ServerConfig());
        PackService brokenLogger = new PackService(root, "1.20.1", "0.19.5", (level, text) -> { throw new IllegalStateException(); });
        var response = brokenLogger.openRequest("GET", "/modsync/v1/manifest", null, null, peer, false);
        assertEquals(200, response.reply().status()); assertDoesNotThrow(() -> response.finish(response.reply().length(), true));
    }
    @Test void standaloneHostAlsoEnforcesSocketIpRulesBeforeServingFiles() throws Exception {
        ServerConfig c = config(); c.deniedIps = List.of("127.0.0.1"); Json.write(configPath, c);
        try (PackServer host = new PackServer(root, "1.20.1", "0.19.5", new InetSocketAddress("127.0.0.1", 0), (level, text) -> logs.add(text))) {
            host.start();
            var client = java.net.http.HttpClient.newHttpClient();
            for (String path : List.of("manifest", "file/sync.jar")) {
                var request = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/modsync/v1/" + path))
                        .header("X-Forwarded-For", "192.0.2.1").GET().build();
                assertEquals(403, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            c.deniedIps = List.of(); c.allowedIps = List.of("127.0.0.0/8"); Json.write(configPath, c);
            var request = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/modsync/v1/manifest")).GET().build();
            assertEquals(200, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
