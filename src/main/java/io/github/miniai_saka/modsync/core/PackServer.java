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
        service = new PackService(game, minecraft, loader);
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
        try {
            PackService.Reply reply = service.prepare(x.getRequestMethod(), x.getRequestURI().getPath(),
                    x.getRequestHeaders().getFirst("X-ModSync-Revision"), x.getRequestHeaders().getFirst("Authorization"));
            if (reply.discarded()) return;
            reply.headers().forEach((k,v) -> x.getResponseHeaders().set(k,v));
            x.sendResponseHeaders(reply.status(), reply.length());
            PackService.writeBody(reply, x.getResponseBody());
        } catch (Exception e) {
            if (x.getResponseCode() == -1) {
                PackService.Reply error = PackService.Reply.text(500, "Sync request failed");
                x.sendResponseHeaders(error.status(), error.length()); x.getResponseBody().write(error.body());
            }
        } finally { x.close(); }
    }
    @Override public void close() { http.stop(0); workers.shutdownNow(); }
}
