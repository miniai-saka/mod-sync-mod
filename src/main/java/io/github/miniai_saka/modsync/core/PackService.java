package io.github.miniai_saka.modsync.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Transport-independent protocol implementation; never opens a listening socket. */
public final class PackService {
    private final Path game, configPath;
    private final String minecraft, loader;
    private volatile Snapshot snapshot;
    private record Snapshot(Manifest manifest, Path source, String token, long rate) {}
    public record Reply(int status, Map<String, String> headers, byte[] body, Path file, long length, long rate) {
        public static Reply text(int code, String text) {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            return new Reply(code, Map.of("Content-Type", "text/plain; charset=utf-8", "Cache-Control", "no-store"), body, null, body.length, 0);
        }
    }
    public PackService(Path game, String minecraft, String loader) throws IOException {
        this.game = game.toAbsolutePath().normalize();
        this.configPath = this.game.resolve("config/fabric-mod-sync-server.json");
        this.minecraft = minecraft; this.loader = loader;
        refresh();
        // Drop obsolete 1.0.0 bindAddress/port fields when opening a server configuration.
        Json.write(configPath, ServerConfig.load(configPath));
    }
    public synchronized Manifest refresh() throws IOException {
        ServerConfig c = ServerConfig.load(configPath);
        Path source = c.source(game);
        SortedMap<String, Hashes.Value> all = Inventory.scan(source);
        boolean changed = !Files.exists(configPath);
        for (String name : all.keySet()) if (!c.files.containsKey(name)) { c.files.put(name, true); changed = true; }
        if (changed) Json.write(configPath, c);
        boolean enabled = c.enabled && (c.syncAllFiles || c.syncSelectedFiles);
        List<Manifest.Entry> files = new ArrayList<>();
        if (enabled) for (var e : all.entrySet()) if (c.selected(e.getKey())) files.add(Manifest.Entry.of(e.getKey(), e.getValue()));
        Manifest m = Manifest.create(enabled && !files.isEmpty(), minecraft, loader, files);
        m.validate(); snapshot = new Snapshot(m, source, c.accessToken, c.maxBytesPerSecond); return m;
    }
    public Reply prepare(String method, String path, String revision, String authorization) throws IOException {
        if (!"GET".equals(method)) return Reply.text(405, "GET required");
        if (!authorized(authorization, ServerConfig.load(configPath).accessToken)) return Reply.text(401, "Access token required");
        if ("/modsync/v1/manifest".equals(path)) {
            Manifest manifest = refresh();
            if (!authorized(authorization, snapshot.token)) return Reply.text(401, "Access token changed");
            byte[] body = Json.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
            return new Reply(200, Map.of("Content-Type", "application/json; charset=utf-8", "Cache-Control", "no-store"), body, null, body.length, 0);
        }
        String prefix = "/modsync/v1/file/";
        if (!path.startsWith(prefix)) return Reply.text(404, "Not found");
        String name;
        try { name = SafePaths.fileName(path.substring(prefix.length())); }
        catch (IOException e) { return Reply.text(400, "Invalid file name"); }
        Snapshot current = snapshot;
        if (!Objects.equals(current.manifest.revision(), revision)) return Reply.text(409, "Server mod list changed; check again");
        Manifest.Entry entry = current.manifest.files().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
        if (!current.manifest.enabled() || entry == null) return Reply.text(404, "Not selected");
        Path file = SafePaths.file(current.source, name);
        try { Hashes.verify(file, entry); }
        catch (IOException e) { return Reply.text(409, "File changed; refresh the mod list"); }
        return new Reply(200, Map.of("Content-Type", "application/java-archive", "Cache-Control", "no-store", "ETag", "\"" + entry.sha256() + "\""),
                null, file, entry.size(), current.rate);
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
