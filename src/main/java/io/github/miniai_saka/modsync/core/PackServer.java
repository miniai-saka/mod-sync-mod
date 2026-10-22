package io.github.miniai_saka.modsync.core;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** The same service is used by the dedicated Fabric server and integration tests. */
public final class PackServer implements AutoCloseable {
    private final Path game, configPath;
    private final String minecraft, loader;
    private final HttpServer http;
    private final ThreadPoolExecutor workers;
    private volatile Snapshot snapshot;
    private record Snapshot(Manifest manifest, Path source, String token, long maxBytesPerSecond) {}

    public PackServer(Path game, String minecraft, String loader) throws IOException {
        this.game = game.toAbsolutePath().normalize();
        this.configPath = this.game.resolve("config/fabric-mod-sync-server.json");
        this.minecraft = minecraft; this.loader = loader;
        ServerConfig config = ServerConfig.load(configPath);
        refresh();
        http = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 32);
        workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "mod-sync-http"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(workers);
        http.createContext("/modsync/v1/", this::handle);
    }
    public void start() { http.start(); }
    public int port() { return http.getAddress().getPort(); }
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
        Manifest m = Manifest.create(enabled, minecraft, loader, files);
        // An empty enabled selection is advertised as disabled, never as an instruction to erase mods.
        if (enabled && files.isEmpty()) m = Manifest.create(false, minecraft, loader, List.of());
        m.validate();
        snapshot = new Snapshot(m, source, c.accessToken, c.maxBytesPerSecond);
        return m;
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            if (!"GET".equals(x.getRequestMethod())) { send(x, 405, "GET required"); return; }
            Snapshot current = snapshot;
            if (!authorized(x, ServerConfig.load(configPath).accessToken)) { send(x, 401, "Access token required"); return; }
            String path = x.getRequestURI().getPath();
            if ("/modsync/v1/manifest".equals(path)) {
                Manifest manifest = refresh();
                if (!authorized(x, snapshot.token)) { send(x, 401, "Access token changed"); return; }
                byte[] json = Json.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                x.getResponseHeaders().set("Cache-Control", "no-store");
                x.sendResponseHeaders(200, json.length);
                x.getResponseBody().write(json);
                return;
            }
            String prefix = "/modsync/v1/file/";
            if (!path.startsWith(prefix)) { send(x, 404, "Not found"); return; }
            String name = SafePaths.fileName(path.substring(prefix.length()));
            if (!Objects.equals(current.manifest.revision(), x.getRequestHeaders().getFirst("X-ModSync-Revision"))) {
                send(x, 409, "Server mod list changed; check again"); return;
            }
            Manifest.Entry entry = current.manifest.files().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
            if (!current.manifest.enabled() || entry == null) { send(x, 404, "Not selected"); return; }
            Path file = SafePaths.file(current.source, name);
            Hashes.verify(file, entry);
            x.getResponseHeaders().set("Content-Type", "application/java-archive");
            x.getResponseHeaders().set("ETag", "\"" + entry.sha256() + "\"");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.sendResponseHeaders(200, entry.size());
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024]; long sent = 0, start = System.nanoTime();
                for (int n; (n = input.read(buffer)) != -1;) {
                    x.getResponseBody().write(buffer, 0, n); sent += n;
                    if (current.maxBytesPerSecond > 0) {
                        long remaining = (long) ((double) sent / current.maxBytesPerSecond * 1_000_000_000L) - (System.nanoTime() - start);
                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                }
            }
        } catch (Exception e) {
            if (x.getResponseCode() == -1) send(x, e instanceof IllegalArgumentException ? 400 : 409, "Sync request failed; refresh the mod list");
        } finally { x.close(); }
    }
    private boolean authorized(HttpExchange x, String token) {
        if (token.isEmpty()) return true;
        String provided = Objects.toString(x.getRequestHeaders().getFirst("Authorization"), "");
        return MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
    private static void send(HttpExchange x, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, bytes.length); x.getResponseBody().write(bytes);
    }
    @Override public void close() { http.stop(0); workers.shutdownNow(); }
}
