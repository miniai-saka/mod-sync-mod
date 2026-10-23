package io.github.miniai_saka.modsync.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.jar.*;

class ServerCacheTest {
    @TempDir Path root;
    private Path configPath;
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(root.resolve("mods"));
        configPath = root.resolve("config/fabric-mod-sync-server.json");
        write("sync.jar", "fabric_mod_sync", "*", "1");
        write("addon.jar", "addon", "*", "1");
    }
    private void write(String file, String id, String environment, String version) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(root.resolve("mods").resolve(file)))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"environment\":\"" + environment + "\"}").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
    private PackService start() throws IOException { return new PackService(root, "1.20.1", "0.19.5"); }
    @Test void startupCacheReusesHashesManifestAndBodyAcrossQueriesAndDownloads() throws Exception {
        PackService service = start();
        assertEquals(2, service.hashComputations());
        Manifest original = service.refresh();
        var first = service.prepare("GET", "/modsync/v1/manifest", null, null);
        for (int i = 0; i < 12; i++) {
            assertSame(original, service.refresh());
            assertSame(first.body(), service.prepare("GET", "/modsync/v1/manifest", null, null).body());
            var reply = service.prepare("GET", "/modsync/v1/file/addon.jar", original.revision(), null);
            assertEquals(200, reply.status());
            ByteArrayOutputStream body = new ByteArrayOutputStream(); PackService.writeBody(reply, body);
            assertArrayEquals(Files.readAllBytes(root.resolve("mods/addon.jar")), body.toByteArray());
        }
        assertEquals(2, service.hashComputations(), "No request may re-hash a jar in the default mode");
    }
    @Test void liveSelectionsChangeManifestWithoutRehashing() throws Exception {
        PackService service = start();
        Manifest before = service.refresh();
        ServerConfig config = ServerConfig.load(configPath); config.syncAllFiles = false; config.files.put("addon.jar", false);
        Json.write(configPath, config);
        Manifest after = service.refresh();
        assertEquals(List.of("sync.jar"), after.files().stream().map(Manifest.Entry::name).toList());
        assertNotEquals(before.revision(), after.revision()); assertEquals(2, service.hashComputations());
        assertEquals(409, service.prepare("GET", "/modsync/v1/file/addon.jar", before.revision(), null).status());
        config.files.put("addon.jar", true); Json.write(configPath, config);
        assertEquals(2, service.refresh().files().size()); assertEquals(2, service.hashComputations());
    }
    @Test void startupRejectsChangedFileAndOnChangeModeRebuildsOnlyChangedHash() throws Exception {
        PackService service = start(); Manifest original = service.refresh();
        write("addon.jar", "addon", "*", "2");
        Files.setLastModifiedTime(root.resolve("mods/addon.jar"), FileTime.fromMillis(System.currentTimeMillis() + 3000));
        assertSame(original, service.refresh());
        assertEquals(409, service.prepare("GET", "/modsync/v1/file/addon.jar", original.revision(), null).status());
        assertEquals(2, service.hashComputations());
        ServerConfig config = ServerConfig.load(configPath); config.hashCacheMode = "on_change"; Json.write(configPath, config);
        Manifest refreshed = service.refresh();
        assertNotEquals(original.revision(), refreshed.revision()); assertEquals(3, service.hashComputations());
        assertEquals(200, service.prepare("GET", "/modsync/v1/file/addon.jar", refreshed.revision(), null).status());
        assertEquals(3, service.hashComputations());
    }
    @Test void newFileDefaultFalseOrTrueIsAppliedWithoutChangingExistingChoices() throws Exception {
        ServerConfig config = new ServerConfig(); config.hashCacheMode = "on_change"; config.syncAllFiles = false; config.newFilesDefaultSelected = false;
        config.files.put("sync.jar", true); Json.write(configPath, config);
        PackService service = start();
        write("later.jar", "later", "client", "1"); service.refresh();
        ServerConfig registered = ServerConfig.load(configPath);
        assertFalse(registered.files.get("later.jar")); assertFalse(registered.files.get("addon.jar"));
        registered.newFilesDefaultSelected = true; Json.write(configPath, registered);
        write("newer.jar", "newer", "*", "1");
        service.refresh(); registered = ServerConfig.load(configPath);
        assertTrue(registered.files.get("newer.jar")); assertFalse(registered.files.get("later.jar"));
        assertEquals(Set.of("sync.jar", "newer.jar"), new HashSet<>(service.refresh().files().stream().map(Manifest.Entry::name).toList()));
    }
    @Test void startupRegistersNewNamesButRequiresRestartBeforeOfferingTheirBytes() throws Exception {
        PackService service = start();
        write("later.jar", "later", "*", "1");
        assertEquals(2, service.refresh().files().size());
        assertTrue(ServerConfig.load(configPath).files.get("later.jar")); assertEquals(2, service.hashComputations());
        PackService restarted = start(); assertEquals(3, restarted.refresh().files().size());
    }
    @Test void serverOnlyMetadataDefaultsToUncheckedButRemainsDownloadable() throws Exception {
        write("server.jar", "server_only", "server", "1");
        PackService service = start(); Manifest manifest = service.refresh();
        var server = manifest.files().stream().filter(e -> e.name().equals("server.jar")).findFirst().orElseThrow();
        assertTrue(server.details().serverOnly()); assertFalse(server.selectedByDefault());
        assertEquals(200, service.prepare("GET", "/modsync/v1/file/server.jar", manifest.revision(), null).status());
        ServerConfig config = ServerConfig.load(configPath); config.excludeServerOnlyByDefault = false; Json.write(configPath, config);
        assertTrue(service.refresh().files().stream().filter(e -> e.name().equals("server.jar")).findFirst().orElseThrow().selectedByDefault());
        assertEquals(3, service.hashComputations());
    }
    @Test void cacheCanBeDisabledAndTransferRehashCanBeEnabledSeparately() throws Exception {
        PackService service = start();
        ServerConfig config = ServerConfig.load(configPath); config.verifyHashesBeforeTransfer = true; Json.write(configPath, config);
        String revision = service.refresh().revision();
        assertEquals(200, service.prepare("GET", "/modsync/v1/file/addon.jar", revision, null).status());
        assertEquals(3, service.hashComputations());
        config.verifyHashesBeforeTransfer = false; config.hashCacheMode = "disabled"; Json.write(configPath, config);
        service.refresh(); assertEquals(5, service.hashComputations());
        service.refresh(); assertEquals(7, service.hashComputations());
        assertTrue(service.prepare("GET", "/", null, null).discarded()); assertEquals(7, service.hashComputations());
    }
}
