package io.github.miniai_saka.modsync.network;

import io.github.miniai_saka.modsync.core.PackService;
import io.github.miniai_saka.modsync.core.SyncAudit;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.*;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.ReferenceCountUtil;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shares the existing Minecraft TCP listener. No extra port or proxy socket is created. */
public final class PortSharing implements AutoCloseable {
    private static final String ACCEPTOR = "modsync-acceptor", SNIFFER = "modsync-sniffer";
    private static final List<byte[]> METHODS = List.of("GET ", "HEAD ", "POST ", "PUT ", "DELETE ", "PATCH ", "OPTIONS ", "CONNECT ", "TRACE ")
            .stream().map(s -> s.getBytes(StandardCharsets.US_ASCII)).toList();
    private final Supplier<PackService> service;
    private final int requestTimeoutSeconds;
    private final ExecutorService workers;
    private final ChannelGroup httpChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    public PortSharing(Supplier<PackService> service) {
        this(service, 30);
    }
    PortSharing(Supplier<PackService> service, int requestTimeoutSeconds) {
        this.service = service;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "mod-sync-http-worker"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public void attach(Channel listener) {
        Runnable install = () -> {
            if (listener.pipeline().get(ACCEPTOR) != null) return;
            listener.pipeline().addFirst(ACCEPTOR, new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                    // Runs before ServerBootstrapAcceptor installs Minecraft's child initializer.
                    if (message instanceof Channel child) child.pipeline().addFirst(SNIFFER, new Sniffer());
                    super.channelRead(ctx, message);
                }
            });
        };
        if (listener.eventLoop().inEventLoop()) install.run();
        else listener.eventLoop().submit(install).syncUninterruptibly();
    }
    public ChannelHandler snifferForTest() { return new Sniffer(); }
    private final class Sniffer extends ByteToMessageDecoder {
        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            int available = in.readableBytes(), start = in.readerIndex();
            boolean possible = false, http = false;
            for (byte[] method : METHODS) {
                boolean matches = true;
                for (int i = 0; i < Math.min(available, method.length); i++) if (in.getByte(start + i) != method[i]) { matches = false; break; }
                if (matches) { possible = true; if (available >= method.length) { http = true; break; } }
            }
            if (!possible) {
                // Removing this decoder forwards its complete, unconsumed cumulation to Minecraft.
                ctx.pipeline().remove(this); return;
            }
            if (!http) return;
            ChannelPipeline p = ctx.pipeline();
            // Keep the sniffer until the replacement HTTP pipeline is ready, so split requests retain every byte.
            for (String name : new ArrayList<>(p.names())) {
                ChannelHandler handler = p.get(name);
                if (handler != null && handler != this) p.remove(name);
            }
            p.addLast("modsync-read-timeout", new ReadTimeoutHandler(requestTimeoutSeconds));
            p.addLast("modsync-write-timeout", new WriteTimeoutHandler(30));
            p.addLast("modsync-http-codec", new HttpServerCodec(4096, 8192, 8192));
            p.addLast("modsync-api-only", new ApiRouteGate());
            p.addLast("modsync-http-handler", new RequestHandler());
            httpChannels.add(ctx.channel());
            p.remove(this);
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
    }
    /** Unknown routes remain silent; their optional logs are processed away from the I/O thread. */
    private final class ApiRouteGate extends ChannelInboundHandlerAdapter {
        private boolean allowed, logged;
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof HttpRequest request) {
                try {
                    allowed = request.decoderResult().isSuccess()
                            && PackService.isSyncPath(new QueryStringDecoder(request.uri(), StandardCharsets.UTF_8).path());
                } catch (RuntimeException e) { allowed = false; }
            }
            if (!allowed) {
                if (!logged) {
                    logged = true;
                    String method = message instanceof HttpRequest request ? request.method().name() : "unknown";
                    InetAddress peer = peer(ctx);
                    try { workers.execute(() -> {
                        PackService current = service.get();
                        if (current != null) current.openRequest(method, "/", null, null, peer, false).finish(0, false);
                    }); } catch (RejectedExecutionException ignored) {}
                }
                ReferenceCountUtil.release(message); ctx.close(); return;
            }
            ctx.fireChannelRead(message);
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
    }
    private static InetAddress peer(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress address ? address.getAddress() : null;
    }
    private final class RequestHandler extends SimpleChannelInboundHandler<HttpObject> {
        private boolean accepted;
        @Override protected void channelRead0(ChannelHandlerContext ctx, HttpObject message) {
            if (!(message instanceof HttpRequest request)) return;
            if (accepted) { ctx.close(); return; }
            accepted = true;
            // A complete GET has no more inbound bytes. Do not time out a healthy long download for lack of reads.
            if (ctx.pipeline().get("modsync-read-timeout") != null) ctx.pipeline().remove("modsync-read-timeout");
            if (!request.decoderResult().isSuccess()) { immediate(ctx, PackService.Reply.text(400, "Invalid HTTP request")); return; }
            String path;
            try { path = new QueryStringDecoder(request.uri(), StandardCharsets.UTF_8).path(); }
            catch (RuntimeException e) { immediate(ctx, PackService.Reply.text(400, "Invalid path encoding")); return; }
            String method = request.method().name(), revision = request.headers().get("X-ModSync-Revision"), auth = request.headers().get("Authorization");
            final boolean hasBody;
            try { hasBody = HttpUtil.isTransferEncodingChunked(request) || HttpUtil.getContentLength(request, 0) != 0; }
            catch (RuntimeException e) { immediate(ctx, PackService.Reply.text(400, "Invalid request length")); return; }
            InetAddress peer = peer(ctx);
            try {
                workers.execute(() -> {
                    PackService.Reply reply;
                    SyncAudit.Response response = null;
                    try {
                        PackService current = service.get();
                        if (current == null) reply = PackService.Reply.text(503, "Minecraft server is starting");
                        else { response = current.openRequest(method, path, revision, auth, peer, hasBody); reply = response.reply(); }
                    } catch (Exception e) { reply = PackService.Reply.text(500, "Sync request failed"); }
                    stream(ctx, reply, response);
                });
            } catch (RejectedExecutionException e) { immediate(ctx, PackService.Reply.text(503, "Sync service is busy")); }
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
    }
    private static void headers(HttpResponse response, PackService.Reply reply) {
        reply.headers().forEach((k,v) -> response.headers().set(k,v));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, reply.length());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().set("X-Content-Type-Options", "nosniff");
    }
    private static void immediate(ChannelHandlerContext ctx, PackService.Reply reply) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(reply.status()), Unpooled.wrappedBuffer(reply.body()));
        headers(response, reply); ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
    private static void stream(ChannelHandlerContext ctx, PackService.Reply reply, SyncAudit.Response audit) {
        long[] sent = {0}; boolean complete = false;
        try {
            if (reply.discarded()) return;
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(reply.status()));
            headers(response, reply); ctx.writeAndFlush(response).sync();
            PackService.writeBody(reply, new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
                @Override public void write(byte[] b, int offset, int length) throws IOException {
                    if (!ctx.channel().isActive()) throw new EOFException("Client disconnected");
                    // At most one 64 KiB buffer per worker is waiting on the socket. No file I/O on the game or Netty thread.
                    ChannelFuture future = ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(b, offset, length)));
                    try { future.sync(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Transfer interrupted"); }
                    catch (RuntimeException e) { throw new IOException("Transfer failed", e); }
                    sent[0] += length;
                }
            });
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).sync();
            complete = true;
        } catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); }
        finally { if (audit != null) audit.finish(sent[0], complete); ctx.close(); }
    }
    @Override public void close() { httpChannels.close(); workers.shutdownNow(); }
}
