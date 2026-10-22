package io.github.miniai_saka.modsync.core;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncTransport {
    private final URI base;
    private final String token;
    private final HttpClient http;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mod-sync-timeouts"); t.setDaemon(true); return t;
    });
    public interface Progress { void update(String file, int index, int count, long done, long total); }
    public SyncTransport(ClientConfig config) throws IOException {
        base = config.baseUri(); token = config.accessToken;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private HttpRequest.Builder request(String path, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).GET();
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return b;
    }
    public Manifest manifest() throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request("modsync/v1/manifest", Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("サーバーへの問い合わせに失敗しました (HTTP " + response.statusCode() + ")");
            var timeout = TIMEOUTS.schedule(() -> close(input), 15, TimeUnit.SECONDS);
            try {
                byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) throw new IOException("Manifest is too large");
                Manifest m;
                try { m = Json.GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Manifest.class); }
                catch (RuntimeException e) { throw new IOException("Invalid server manifest", e); }
                if (m == null) throw new IOException("Empty server manifest");
                m.validate(); return m;
            } finally { timeout.cancel(false); }
        }
    }
    public Path stage(Path game, SyncPlan plan, Progress progress, AtomicBoolean cancel) throws IOException, InterruptedException {
        plan.validate();
        Path root = game.toAbsolutePath().normalize().resolve(".modsync");
        SafePaths.noLinks(root);
        SafePaths.noLinks(root.resolve("transactions"));
        Files.createDirectories(root.resolve("transactions"));
        Path stage = Files.createDirectory(root.resolve("transactions").resolve(UUID.randomUUID().toString()));
        Files.createDirectory(stage.resolve("downloads"));
        long total = plan.downloadBytes(), done = 0;
        if (Files.getFileStore(stage).getUsableSpace() < total + 64L * 1024 * 1024) throw new IOException("同期に必要な空き容量がありません");
        int index = 0;
        for (Manifest.Entry entry : plan.downloads()) {
            checkCancelled(cancel); index++;
            String encoded = URLEncoder.encode(entry.name(), StandardCharsets.UTF_8).replace("+", "%20");
            HttpRequest req = request("modsync/v1/file/" + encoded, Duration.ofSeconds(30))
                    .header("X-ModSync-Revision", plan.manifest().revision()).build();
            HttpResponse<InputStream> response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            Path part = stage.resolve("downloads").resolve(entry.name() + ".part");
            long fileDone = 0;
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) throw new IOException("ダウンロードに失敗しました: " + entry.name() + " (HTTP " + response.statusCode() + ")");
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (length != entry.size()) throw new IOException("File size header mismatch: " + entry.name());
                java.util.concurrent.atomic.AtomicLong lastRead = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
                var guard = TIMEOUTS.scheduleAtFixedRate(() -> {
                    if (cancel.get() || System.nanoTime() - lastRead.get() > TimeUnit.SECONDS.toNanos(30)) close(input);
                }, 1, 1, TimeUnit.SECONDS);
                try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[128 * 1024];
                    for (int n; (n = input.read(buffer)) != -1;) {
                        checkCancelled(cancel); lastRead.set(System.nanoTime()); fileDone += n;
                        if (fileDone > entry.size()) throw new IOException("Oversized download: " + entry.name());
                        out.write(buffer, 0, n); progress.update(entry.name(), index, plan.downloads().size(), done + fileDone, total);
                    }
                } finally { guard.cancel(false); }
            }
            Hashes.verify(part, entry);
            try (var jar = new java.util.jar.JarFile(part.toFile())) {
                if (jar.getJarEntry("fabric.mod.json") == null) throw new IOException("Fabric Modではありません: " + entry.name());
            }
            Json.move(part, SafePaths.file(stage.resolve("downloads"), entry.name()));
            done += fileDone;
        }
        checkCancelled(cancel);
        Manifest fresh = manifest();
        if (!fresh.revision().equals(plan.manifest().revision())) throw new IOException("同期中にサーバー構成が変更されました。もう一度確認してください。");
        if (!Inventory.scan(game.resolve("mods")).equals(plan.before())) throw new IOException("同期中にローカルのModが変更されました。もう一度確認してください。");
        PackValidation.requireSyncMod(game, stage, plan);
        Json.write(stage.resolve("plan.json"), plan);
        progress.update("MD5 / SHA-256 照合完了", index, plan.downloads().size(), total, total);
        return stage;
    }
    private static void close(InputStream input) { try { input.close(); } catch (IOException ignored) {} }
    private static void checkCancelled(AtomicBoolean cancel) throws InterruptedIOException {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("同期をキャンセルしました");
    }
}
