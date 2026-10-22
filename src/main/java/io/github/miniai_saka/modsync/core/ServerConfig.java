package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ServerConfig {
    public boolean enabled = true;
    public String sourceDirectory = "mods";
    public boolean syncAllFiles = true;
    public boolean syncSelectedFiles = true;
    public String accessToken = "";
    public long maxBytesPerSecond = 0;
    public Map<String, Boolean> files = new TreeMap<>();
    public static ServerConfig load(Path path) throws IOException {
        ServerConfig c = Files.exists(path) ? Json.read(path, ServerConfig.class) : new ServerConfig();
        if (c.sourceDirectory == null || c.files == null || c.accessToken == null || c.maxBytesPerSecond < 0)
            throw new IOException("Invalid server config");
        return c;
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
