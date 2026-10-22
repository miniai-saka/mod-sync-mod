package io.github.miniai_saka.modsync.client;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

class WindowsArgumentsTest {
    @Test void capturesWindowsCommandIncludingSpacesAndUnicode() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        String java = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
        String cp = Path.of(WindowsArgumentsProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(WindowsArguments.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(com.sun.jna.Native.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(java, "-Dmodsync.test.sentinel=C:\\space dir\\日本語\\", "-cp", cp,
                WindowsArgumentsProbe.class.getName(), "space value", "日本語").redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            String result = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), result); assertTrue(result.contains("OK"));
        } finally { child.destroyForcibly(); }
    }
}
