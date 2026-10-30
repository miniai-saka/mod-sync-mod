package io.github.miniai_saka.modsync.network;

import io.github.miniai_saka.modsync.core.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
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

class PortSharingTest {
    @TempDir Path root;
    private NioEventLoopGroup boss, io;
    private Channel listener;
    private PortSharing sharing;
    private PackService service;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private int port;
    private Path game, server;
    @BeforeEach void start() throws Exception {
        server = Files.createDirectories(root.resolve("server/mods")).getParent();
        game = Files.createDirectories(root.resolve("client/mods")).getParent();
        writeMod("sync.jar", "fabric_mod_sync", 4096);
        ServerConfig config = new ServerConfig(); config.hashCacheMode = "on_change";
        Json.write(server.resolve("config/fabric-mod-sync-server.json"), config);
        service = new PackService(server, "1.20.1", "0.19.5", (level, text) -> logs.add(level + " " + text));
        sharing = new PortSharing(() -> service, 1);
        boss = new NioEventLoopGroup(1); io = new NioEventLoopGroup(1);
        listener = new ServerBootstrap().group(boss, io).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        channel.pipeline().addLast("minecraft-timeout", new ReadTimeoutHandler(2));
                        channel.pipeline().addLast("minecraft-test", new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) { ctx.writeAndFlush(bytes.retainedDuplicate()); }
                            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
                        });
                    }
                }).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        sharing.attach(listener); port = ((InetSocketAddress) listener.localAddress()).getPort();
    }
    @AfterEach void stop() {
        if (sharing != null) sharing.close(); if (listener != null) listener.close().syncUninterruptibly();
        if (boss != null) boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        if (io != null) io.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }
    private void writeMod(String name, String id, int size) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(server.resolve("mods").resolve(name)))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"}").getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new JarEntry("data")); byte[] data = new byte[size]; new Random(7).nextBytes(data); out.write(data); out.closeEntry();
        }
    }
    private SyncTransport transport(String token) throws IOException {
        ClientConfig c = new ClientConfig(); c.server = "127.0.0.1:" + port; c.accessToken = token; return new SyncTransport(c);
    }
    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void sameListenerServesHttpAndPreservesMinecraftBytes() throws Exception {
        assertEquals(200, request("GET", "/modsync/v1/manifest", "").statusCode());
        byte[] vanilla = {0x10, 0x00, (byte) 0xF8, 0x05, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', 0x63, (byte) 0xDD, 1};
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000); socket.getOutputStream().write(vanilla);
            assertArrayEquals(vanilla, socket.getInputStream().readNBytes(vanilla.length));
        }
    }
    @Test void splitPossibleHttpPrefixDoesNotConsumeMinecraftBytes() throws Exception {
        byte[] bytes = {'G', 0, 1, 2, 3};
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000); socket.getOutputStream().write(bytes, 0, 1); socket.getOutputStream().flush();
            Thread.sleep(30); socket.getOutputStream().write(bytes, 1, bytes.length - 1);
            assertArrayEquals(bytes, socket.getInputStream().readNBytes(bytes.length));
        }
    }
    @Test void splitHttpRequestIsDecodedOnceAndConnectionCloses() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000); socket.getOutputStream().write('G'); socket.getOutputStream().flush();
            Thread.sleep(30); socket.getOutputStream().write("ET /modsync/v1/manifest HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200")); assertTrue(response.toLowerCase(Locale.ROOT).contains("connection: close"));
            assertTrue(response.contains("\"protocol\": 1"));
        }
    }
    @Test void realTransportDownloadsAndInstallsViaSharedListener() throws Exception {
        writeMod("日本語 + addon.jar", "addon", 128 * 1024);
        SyncTransport transport = transport(""); SyncPlan plan = SyncPlan.create(transport.manifest(), Inventory.scan(game.resolve("mods")));
        Path stage = transport.stage(game, plan, (a,b,c,d,e) -> {}, new AtomicBoolean());
        Transaction.apply(game, stage); Transaction.verifyExact(game, plan.manifest());
        assertEquals(2, Inventory.scan(game.resolve("mods")).size());
    }
    @Test void authenticationErrorsAndMethodsRemainHttpResponses() throws Exception {
        ServerConfig config = ServerConfig.load(server.resolve("config/fabric-mod-sync-server.json")); config.accessToken = "secret";
        Json.write(server.resolve("config/fabric-mod-sync-server.json"), config);
        assertEquals(401, request("GET", "/modsync/v1/manifest", "").statusCode());
        assertEquals(200, request("GET", "/modsync/v1/manifest", "secret").statusCode());
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET /unknown HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals(0, socket.getInputStream().readAllBytes().length);
        }
        assertEquals(405, request("POST", "/modsync/v1/manifest", "secret").statusCode());
    }
    @Test void slowDownloadDoesNotBlockNormalTraffic() throws Exception {
        writeMod("large.jar", "large", 512 * 1024);
        ServerConfig config = ServerConfig.load(server.resolve("config/fabric-mod-sync-server.json")); config.maxBytesPerSecond = 256 * 1024;
        Json.write(server.resolve("config/fabric-mod-sync-server.json"), config);
        SyncTransport transport = transport(""); SyncPlan plan = SyncPlan.create(transport.manifest(), Inventory.scan(game.resolve("mods")));
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Path> transfer = CompletableFuture.supplyAsync(() -> {
            try { return transport.stage(game, plan, (a,b,c,d,e) -> started.countDown(), new AtomicBoolean()); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(1000); socket.getOutputStream().write(new byte[]{3,0,1});
            assertArrayEquals(new byte[]{3,0,1}, socket.getInputStream().readNBytes(3));
        }
        assertNotNull(transfer.get(10, TimeUnit.SECONDS));
    }
    @Test void incompleteHttpPrefixRetainsOriginalIdleTimeout() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(4000); socket.getOutputStream().write('G'); assertEquals(-1, socket.getInputStream().read());
        }
    }
    @Test void unknownRoutesIncludingRootNeverSendHttpStatusHeadersOrBody() throws Exception {
        for (String path : List.of("/", "/favicon.ico", "/robots.txt", "/modsync/v1/unknown", "/modsync/v1/manifest/")) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                assertEquals(-1, socket.getInputStream().read(), path);
            }
        }
    }
    @Test void unknownRouteWithBodyDoesNotReceiveAutomaticContinueOrEntityTooLarge() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\nExpect: 100-continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals(-1, socket.getInputStream().read());
        }
    }
    private Path configPath() { return server.resolve("config/fabric-mod-sync-server.json"); }
    private String awaitLog(java.util.function.Predicate<String> test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            for (String log : logs) if (test.test(log)) return log;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Expected log not found: " + logs); return "";
    }
    @Test void socketPeerRestrictionsCoverBothApisIgnoreForwardingHeadersAndKeepMinecraftTraffic() throws Exception {
        ServerConfig c = ServerConfig.load(configPath()); c.deniedIps = List.of("127.0.0.0/8"); Json.write(configPath(), c);
        var spoofed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/modsync/v1/manifest"))
                .header("X-Forwarded-For", "192.0.2.1").header("Forwarded", "for=192.0.2.1").GET().build();
        assertEquals(403, HttpClient.newHttpClient().send(spoofed, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(403, request("GET", "/modsync/v1/file/sync.jar", "").statusCode());
        String rejection = awaitLog(log -> log.contains("reason=ip_denied")); assertTrue(rejection.contains("ip=127.0.0.1"));
        assertFalse(rejection.contains("192.0.2.1"));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000); socket.getOutputStream().write(new byte[]{3,0,1});
            assertArrayEquals(new byte[]{3,0,1}, socket.getInputStream().readNBytes(3));
        }
    }
    @Test void allowedIpsAndLoggingChangesApplyWithoutServerRestart() throws Exception {
        ServerConfig c = ServerConfig.load(configPath()); c.allowedIps = List.of("192.0.2.1"); Json.write(configPath(), c);
        assertEquals(403, request("GET", "/modsync/v1/manifest", "").statusCode());
        awaitLog(log -> log.contains("reason=ip_not_allowed")); logs.clear();
        c.allowedIps = List.of("127.0.0.1/32"); c.logMode = "detailed"; Json.write(configPath(), c);
        assertEquals(200, request("GET", "/modsync/v1/manifest", "").statusCode());
        assertTrue(awaitLog(log -> log.contains("success")).contains("elapsed_ms=")); logs.clear();
        c.logMode = "off"; Json.write(configPath(), c);
        assertEquals(200, request("GET", "/modsync/v1/manifest", "").statusCode());
        c.deniedIps = List.of("127.0.0.1"); Json.write(configPath(), c);
        assertEquals(403, request("GET", "/modsync/v1/manifest", "").statusCode());
        Thread.sleep(100); assertTrue(logs.isEmpty());
    }
    @Test void completedFileLogsExactlyItsBodyBytesAndSuccessIsNotLoggedBeforeCompletion() throws Exception {
        ServerConfig c = ServerConfig.load(configPath()); c.logSuccessfulRequests = false; c.logMode = "detailed"; Json.write(configPath(), c);
        var manifest = transport("").manifest();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/modsync/v1/file/sync.jar"))
                .header("X-ModSync-Revision", manifest.revision()).GET().build();
        byte[] received = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
        assertArrayEquals(Files.readAllBytes(server.resolve("mods/sync.jar")), received);
        String log = awaitLog(text -> text.contains("path=/modsync/v1/file/sync.jar") && text.contains("success"));
        assertTrue(log.contains("bytes=" + received.length + " expected_bytes=" + received.length));
        assertEquals(1, logs.size());
    }
    @Test void disconnectedFileDownloadLogsPartialBytesAndNeverSuccess() throws Exception {
        writeMod("large.jar", "large", 2 * 1024 * 1024);
        ServerConfig c = ServerConfig.load(configPath()); c.maxBytesPerSecond = 64 * 1024; c.logMode = "detailed"; Json.write(configPath(), c);
        var manifest = transport("").manifest();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000); socket.setSoLinger(true, 0);
            socket.getOutputStream().write(("GET /modsync/v1/file/large.jar HTTP/1.1\r\nHost: localhost\r\nX-ModSync-Revision: "
                    + manifest.revision() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            StringBuilder header = new StringBuilder();
            while (!header.toString().endsWith("\r\n\r\n")) { int b = socket.getInputStream().read(); assertNotEquals(-1, b); header.append((char) b); }
            assertTrue(header.toString().startsWith("HTTP/1.1 200")); assertNotEquals(-1, socket.getInputStream().read());
        }
        String aborted = awaitLog(log -> log.contains("aborted") && log.contains("large.jar"));
        long bytes = Long.parseLong(aborted.split(" bytes=")[1].split(" ")[0]);
        assertTrue(bytes < Files.size(server.resolve("mods/large.jar")));
        assertTrue(aborted.contains("reason=transfer_incomplete"));
        assertTrue(logs.stream().noneMatch(log -> log.contains("large.jar") && log.contains("success")));
    }
    @Test void ipDeniedBodyRequestsCannotReceiveContinueOrBypassChecks() throws Exception {
        ServerConfig c = ServerConfig.load(configPath()); c.deniedIps = List.of("127.0.0.1"); Json.write(configPath(), c);
        for (int expected : new int[]{403, 413}) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("GET /modsync/v1/manifest HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\nExpect: 100-continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 " + expected)); assertFalse(response.contains("100 Continue"));
            }
            c.deniedIps = List.of(); Json.write(configPath(), c);
        }
    }
    @Test void ipv6SocketRulesUseTheRealIpv6Peer() throws Exception {
        Channel ipv6 = new ServerBootstrap().group(boss, io).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() { @Override protected void initChannel(Channel channel) {} })
                .bind(new InetSocketAddress("::1", 0)).sync().channel();
        try {
            sharing.attach(ipv6); int ipv6Port = ((InetSocketAddress) ipv6.localAddress()).getPort();
            ServerConfig c = ServerConfig.load(configPath()); c.allowedIps = List.of("::1/128"); Json.write(configPath(), c);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://[::1]:" + ipv6Port + "/modsync/v1/manifest")).GET().build();
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            c.deniedIps = List.of("::1"); Json.write(configPath(), c);
            assertEquals(403, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally { ipv6.close().syncUninterruptibly(); }
    }
}
