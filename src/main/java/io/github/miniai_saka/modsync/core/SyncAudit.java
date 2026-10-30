package io.github.miniai_saka.modsync.core;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transport completion logging without a dependency on Fabric or SLF4J in the helper jar. */
public final class SyncAudit {
    public enum Level { INFO, WARN }
    @FunctionalInterface public interface Sink { void write(Level level, String message); }
    public record Settings(String mode, boolean rejected, boolean successful, boolean transfers) {
        public static Settings from(ServerConfig c) {
            return new Settings(c.logMode, c.logRejectedRequests, c.logSuccessfulRequests, c.logTransfers);
        }
    }
    public static Sink systemLogger() {
        System.Logger logger = System.getLogger("FabricModSync");
        return (level, message) -> logger.log(level == Level.WARN ? System.Logger.Level.WARNING : System.Logger.Level.INFO, message);
    }
    public static final class Response {
        private final PackService.Reply reply;
        private final Settings settings;
        private final Sink sink;
        private final String peer, method, path, reason;
        private final long started;
        private final AtomicBoolean finished = new AtomicBoolean();
        Response(PackService.Reply reply, Settings settings, Sink sink, InetAddress peer, String method, String path, String reason, long started) {
            this.reply = reply; this.settings = settings; this.sink = sink; this.started = started;
            this.peer = peer == null ? "unknown" : peer.getHostAddress();
            this.method = clean(method, 16);
            // Never log raw query strings or arbitrary non-API URLs, which may contain secrets.
            this.path = PackService.isSyncPath(path) ? clean(path, 240) : "<outside-sync-api>";
            this.reason = reason;
        }
        public PackService.Reply reply() { return reply; }
        public void finish(long bytes, boolean complete) {
            if (!finished.compareAndSet(false, true) || "off".equals(settings.mode)) return;
            boolean file = reply.file() != null;
            boolean success = !reply.discarded() && reply.status() < 400 && complete && bytes == reply.length();
            if (success ? !(settings.successful || (file && settings.transfers)) : !(settings.rejected || (file && settings.transfers))) return;
            String outcome = success ? "success" : reply.discarded() || reply.status() >= 400 ? "rejected" : "aborted";
            String message = "Mod sync " + outcome + " ip=" + clean(peer, 80) + " method=" + method
                    + " path=" + path + " status=" + reply.status();
            if (!success) message += " reason=" + ("aborted".equals(outcome) ? "transfer_incomplete" : reason);
            if (file && settings.transfers) message += " bytes=" + bytes;
            if ("detailed".equals(settings.mode)) {
                if (file && settings.transfers) message += " expected_bytes=" + reply.length();
                message += " elapsed_ms=" + Math.max(0, (System.nanoTime() - started) / 1_000_000);
            }
            try { sink.write(success ? Level.INFO : Level.WARN, message); }
            catch (RuntimeException ignored) { /* A logging backend must not change a sync result. */ }
        }
    }
    private SyncAudit() {}
    private static String clean(String value, int limit) {
        if (value == null) return "unknown";
        StringBuilder result = new StringBuilder();
        value.codePoints().limit(limit).forEach(c -> result.appendCodePoint(Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || Character.isWhitespace(c) ? '_' : c));
        return result.toString();
    }
}
