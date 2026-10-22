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
    private int port;
    private Path game, server;
    @BeforeEach void start() throws Exception {
        server = Files.createDirectories(root.resolve("server/mods")).getParent();
        game = Files.createDirectories(root.resolve("client/mods")).getParent();
        writeMod("sync.jar", "fabric_mod_sync", 4096);
        service = new PackService(server, "1.20.1", "0.19.5");
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
        assertEquals(404, request("GET", "/unknown", "secret").statusCode());
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
}
