package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

public final class SafePaths {
    private SafePaths() {}
    public static String fileName(String name) throws IOException {
        if (name == null || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                || name.startsWith(".") || name.endsWith(" ") || name.contains("..")
                || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?"))
            throw new IOException("Unsafe mod file name: " + name);
        return name;
    }
    public static Path file(Path root, String name) throws IOException {
        Path result = root.toAbsolutePath().normalize().resolve(fileName(name));
        noLinks(root);
        if (Files.isSymbolicLink(result) || (Files.exists(result, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Not a regular mod file: " + name);
        return result;
    }
    public static void noLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("Linked/reparse path is not allowed: " + p);
        }
    }
}
