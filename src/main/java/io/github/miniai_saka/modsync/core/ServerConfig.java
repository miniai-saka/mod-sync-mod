package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ServerConfig {
    public boolean enabled = true;
    public String sourceDirectory = "mods";
    public boolean syncAllFiles = true;
    public boolean syncSelectedFiles = true;
    public boolean newFilesDefaultSelected = true;
    public boolean excludeServerOnlyByDefault = true;
    public String hashCacheMode = "startup";
    public boolean verifyHashesBeforeTransfer = false;
    public String accessToken = "";
    public long maxBytesPerSecond = 0;
    public List<String> allowedIps = new ArrayList<>();
    public List<String> deniedIps = new ArrayList<>();
    public String logMode = "normal";
    public boolean logRejectedRequests = true;
    public boolean logSuccessfulRequests = true;
    public boolean logTransfers = true;
    private transient IpAccess ipAccess;
    public Map<String, Boolean> files = new TreeMap<>();
    public static ServerConfig load(Path path) throws IOException {
        ServerConfig c = Files.exists(path) ? Json.read(path, ServerConfig.class) : new ServerConfig();
        if (c.sourceDirectory == null || c.files == null || c.files.size() > 4096 || c.accessToken == null || c.maxBytesPerSecond < 0
                || c.hashCacheMode == null || !Set.of("startup", "on_change", "disabled").contains(c.hashCacheMode)
                || c.logMode == null || !Set.of("off", "normal", "detailed").contains(c.logMode))
            throw new IOException("Invalid server config");
        c.ipAccess = IpAccess.compile(c.allowedIps, c.deniedIps);
        for (var entry : c.files.entrySet()) {
            SafePaths.fileName(entry.getKey());
            if (entry.getValue() == null) throw new IOException("Invalid file selection");
        }
        return c;
    }
    public IpAccess ipAccess() throws IOException {
        return ipAccess == null ? IpAccess.compile(allowedIps, deniedIps) : ipAccess;
    }
    public Path source(Path game) throws IOException {
        Path relative = Path.of(sourceDirectory);
        Path root = game.toAbsolutePath().normalize();
        Path result = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !result.startsWith(root) || result.equals(root)) throw new IOException("sourceDirectory must stay inside server directory");
        SafePaths.noLinks(result);
        return result;
    }
    public boolean selected(String name) { return syncAllFiles || (syncSelectedFiles && Boolean.TRUE.equals(files.get(name))); }
}
