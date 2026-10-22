package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() {}
    public record Value(long size, String md5, String sha256) {}
    public static Value of(Path file) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5"), sha = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                for (int n; (n = input.read(buffer)) != -1;) { md5.update(buffer, 0, n); sha.update(buffer, 0, n); size += n; }
            }
            return new Value(size, HexFormat.of().formatHex(md5.digest()), HexFormat.of().formatHex(sha.digest()));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static void verify(Path file, Manifest.Entry entry) throws IOException {
        if (!of(file).equals(entry.hash())) throw new IOException("MD5/SHA-256 verification failed: " + entry.name());
    }
}
