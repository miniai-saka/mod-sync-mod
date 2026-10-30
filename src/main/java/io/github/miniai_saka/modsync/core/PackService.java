package io.github.miniai_saka.modsync.core;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Transport-independent protocol implementation; never opens a listening socket. */
public final class PackService {
    private final Path game, configPath;
    private final String minecraft, loader;
    private final ServerCatalog catalog = new ServerCatalog();
    private final SyncAudit.Sink audit;
    private volatile SyncAudit.Settings logSettings = SyncAudit.Settings.from(new ServerConfig());
    private volatile Snapshot snapshot;
    private record Snapshot(Manifest manifest, Path source, String token, long rate, boolean verifyTransfer, byte[] json) {}
    public record Reply(int status, Map<String, String> headers, byte[] body, Path file, long length, long rate) {
        public static Reply drop() { return new Reply(0, Map.of(), new byte[0], null, 0, 0); }
        public boolean discarded() { return status == 0; }
        public static Reply text(int code, String text) {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            return new Reply(code, Map.of("Content-Type", "text/plain; charset=utf-8", "Cache-Control", "no-store"), body, null, body.length, 0);
        }
    }
    public PackService(Path game, String minecraft, String loader) throws IOException {
        this(game, minecraft, loader, SyncAudit.systemLogger());
    }
    public PackService(Path game, String minecraft, String loader, SyncAudit.Sink audit) throws IOException {
        this.audit = Objects.requireNonNull(audit);
        this.game = game.toAbsolutePath().normalize();
        this.configPath = this.game.resolve("config/fabric-mod-sync-server.json");
        this.minecraft = minecraft; this.loader = loader;
        refresh();
        // Drop obsolete 1.0.0 bindAddress/port fields when opening a server configuration.
        Json.write(configPath, ServerConfig.load(configPath));
    }
    public synchronized Manifest refresh() throws IOException {
        return refresh(ServerConfig.load(configPath));
    }
    private Manifest refresh(ServerConfig c) throws IOException {
        logSettings = SyncAudit.Settings.from(c);
        Path source = c.source(game);
        SortedMap<String, Path> paths = Inventory.paths(source);
        SortedMap<String, ServerCatalog.Jar> all = catalog.refresh(source, paths, c.hashCacheMode);
        boolean changed = !Files.exists(configPath);
        for (String name : paths.keySet()) if (!c.files.containsKey(name)) { c.files.put(name, c.newFilesDefaultSelected); changed = true; }
        if (changed) Json.write(configPath, c);
        boolean enabled = c.enabled && (c.syncAllFiles || c.syncSelectedFiles);
        List<Manifest.Entry> files = new ArrayList<>();
        if (enabled) for (var e : all.entrySet()) if (c.selected(e.getKey())) {
            var hash = e.getValue().hash();
            var details = e.getValue().details();
            files.add(new Manifest.Entry(e.getKey(), hash.size(), hash.md5(), hash.sha256(), details,
                    !(c.excludeServerOnlyByDefault && details.serverOnly())));
        }
        boolean active = enabled && !files.isEmpty();
        Snapshot previous = snapshot;
        Manifest m = previous != null && previous.manifest.enabled() == active && previous.manifest.files().equals(files)
                ? previous.manifest : Manifest.create(active, minecraft, loader, files);
        if (previous == null || previous.manifest != m) m.validate();
        byte[] json = previous != null && previous.manifest == m ? previous.json : Json.GSON.toJson(m).getBytes(StandardCharsets.UTF_8);
        snapshot = new Snapshot(m, source, c.accessToken, c.maxBytesPerSecond, c.verifyHashesBeforeTransfer, json);
        return m;
    }
    long hashComputations() { return catalog.computations(); }
    public static boolean isSyncPath(String path) {
        if ("/modsync/v1/manifest".equals(path)) return true;
        if (path == null || !path.startsWith("/modsync/v1/file/")) return false;
        try { SafePaths.fileName(path.substring("/modsync/v1/file/".length())); return true; }
        catch (IOException e) { return false; }
    }
    public Reply prepare(String method, String path, String revision, String authorization) throws IOException {
        return openRequest(method, path, revision, authorization, null, false).reply();
    }
    public synchronized SyncAudit.Response openRequest(String method, String path, String revision, String authorization,
                                                       InetAddress peer, boolean hasBody) {
        long started = System.nanoTime();
        String failure = "invalid_config";
        Decision decision;
        try {
            ServerConfig c = ServerConfig.load(configPath);
            logSettings = SyncAudit.Settings.from(c);
            failure = "request_failed";
            decision = decide(c, method, path, revision, authorization, peer, hasBody);
        } catch (IOException | RuntimeException e) {
            // Never fall back to unrestricted access or expose filesystem paths/config contents.
            decision = new Decision(isSyncPath(path) ? Reply.text(500, "Sync request failed; check server config") : Reply.drop(), failure);
        }
        return new SyncAudit.Response(decision.reply, logSettings, audit, peer, method, path, decision.reason, started);
    }
    private record Decision(Reply reply, String reason) {}
    private Decision decide(ServerConfig c, String method, String path, String revision, String authorization,
                            InetAddress peer, boolean hasBody) throws IOException {
        if (!isSyncPath(path)) return new Decision(Reply.drop(), "outside_sync_api");
        String rejection = c.ipAccess().rejection(peer);
        if (rejection != null) return new Decision(Reply.text(403, "IP address is not allowed"), rejection);
        if (!authorized(authorization, c.accessToken)) return new Decision(Reply.text(401, "Access token required"), "token_required");
        if (!"GET".equals(method)) return new Decision(Reply.text(405, "GET required"), "method_not_allowed");
        if (hasBody) return new Decision(Reply.text(413, "Request body is not supported"), "body_not_supported");
        refresh(c);
        Snapshot current = snapshot;
        if ("/modsync/v1/manifest".equals(path)) {
            return new Decision(new Reply(200, Map.of("Content-Type", "application/json; charset=utf-8", "Cache-Control", "no-store"), current.json, null, current.json.length, 0), "ok");
        }
        String prefix = "/modsync/v1/file/";
        String name = SafePaths.fileName(path.substring(prefix.length()));
        if (!Objects.equals(current.manifest.revision(), revision)) return new Decision(Reply.text(409, "Server mod list changed; check again"), "revision_mismatch");
        Manifest.Entry entry = current.manifest.files().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
        if (!current.manifest.enabled() || entry == null) return new Decision(Reply.text(404, "Not selected"), "file_not_selected");
        Path file = SafePaths.file(current.source, name);
        if (!catalog.unchanged(name, file)) return new Decision(Reply.text(409, "File changed; restart the server or use hashCacheMode=on_change"), "file_changed");
        if (current.verifyTransfer && !catalog.hash(file).equals(entry.hash()))
            return new Decision(Reply.text(409, "File hash changed; refresh the server cache"), "file_hash_changed");
        return new Decision(new Reply(200, Map.of("Content-Type", "application/java-archive", "Cache-Control", "no-store", "ETag", "\"" + entry.sha256() + "\""),
                null, file, entry.size(), current.rate), "ok");
    }
    private static boolean authorized(String provided, String token) {
        return token.isEmpty() || MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                Objects.toString(provided, "").getBytes(StandardCharsets.UTF_8));
    }
    public static void writeBody(Reply reply, OutputStream out) throws IOException, InterruptedException {
        if (reply.body != null) { out.write(reply.body); return; }
        try (var input = Files.newInputStream(reply.file)) {
            byte[] buffer = new byte[64 * 1024]; long sent = 0, start = System.nanoTime();
            for (int n; (n = input.read(buffer)) != -1;) {
                out.write(buffer, 0, n); sent += n;
                if (reply.rate > 0) {
                    long remaining = (long) ((double) sent / reply.rate * 1_000_000_000L) - (System.nanoTime() - start);
                    if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                }
            }
        }
    }
}
