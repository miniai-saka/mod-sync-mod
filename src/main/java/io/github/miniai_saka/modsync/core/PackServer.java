package io.github.miniai_saka.modsync.core;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;

/** Optional standalone/test HTTP host. Dedicated Minecraft servers use PortSharing instead. */
public final class PackServer implements AutoCloseable {
    private final PackService service;
    private final HttpServer http;
    private final ExecutorService workers;
    public PackServer(Path game, String minecraft, String loader) throws IOException {
        this(game, minecraft, loader, standaloneAddress(game));
    }
    public PackServer(Path game, String minecraft, String loader, InetSocketAddress address) throws IOException {
        this(game, minecraft, loader, address, SyncAudit.systemLogger());
    }
    public PackServer(Path game, String minecraft, String loader, InetSocketAddress address, SyncAudit.Sink audit) throws IOException {
        service = new PackService(game, minecraft, loader, audit);
        http = HttpServer.create(address, 32);
        workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "mod-sync-standalone"); t.setDaemon(true); return t;
        });
        http.setExecutor(workers); http.createContext("/", this::handle);
    }
    private static InetSocketAddress standaloneAddress(Path game) throws IOException {
        Properties p = new Properties(); Path path = game.resolve("server.properties");
        if (Files.exists(path)) try (var input = Files.newInputStream(path)) { p.load(input); }
        String bind = p.getProperty("server-ip", "").strip();
        try { return new InetSocketAddress(bind.isEmpty() ? "0.0.0.0" : bind, Integer.parseInt(p.getProperty("server-port", "25565"))); }
        catch (RuntimeException e) { throw new IOException("Invalid server-port in server.properties", e); }
    }
    public void start() { http.start(); }
    public int port() { return http.getAddress().getPort(); }
    public Manifest refresh() throws IOException { return service.refresh(); }
    private void handle(HttpExchange x) throws IOException {
        SyncAudit.Response response = null;
        long[] sent = {0}; boolean complete = false;
        try {
            String length = x.getRequestHeaders().getFirst("Content-Length");
            boolean hasBody = x.getRequestHeaders().containsKey("Transfer-Encoding") || (length != null && !"0".equals(length));
            response = service.openRequest(x.getRequestMethod(), x.getRequestURI().getPath(),
                    x.getRequestHeaders().getFirst("X-ModSync-Revision"), x.getRequestHeaders().getFirst("Authorization"),
                    x.getRemoteAddress().getAddress(), hasBody);
            PackService.Reply reply = response.reply();
            if (reply.discarded()) return;
            reply.headers().forEach((k,v) -> x.getResponseHeaders().set(k,v));
            x.sendResponseHeaders(reply.status(), reply.length());
            OutputStream body = x.getResponseBody();
            PackService.writeBody(reply, new OutputStream() {
                @Override public void write(int b) throws IOException { body.write(b); sent[0]++; }
                @Override public void write(byte[] b, int offset, int count) throws IOException { body.write(b, offset, count); sent[0] += count; }
            });
            body.close(); complete = true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (x.getResponseCode() == -1) {
                PackService.Reply error = PackService.Reply.text(500, "Sync request failed");
                x.sendResponseHeaders(error.status(), error.length()); x.getResponseBody().write(error.body());
            }
        } finally { x.close(); if (response != null) response.finish(sent[0], complete); }
    }
    @Override public void close() { http.stop(0); workers.shutdownNow(); }
}
