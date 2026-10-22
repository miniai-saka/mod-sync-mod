package io.github.miniai_saka.modsync.client;

public final class WindowsArgumentsProbe {
    public static void main(String[] args) throws Exception {
        var captured = WindowsArguments.current();
        if (!captured.contains("space value") || !captured.contains("日本語")
                || !captured.contains("-Dmodsync.test.sentinel=C:\\space dir\\日本語\\"))
            throw new AssertionError("Windows launch argument quoting was not preserved");
        System.out.println("OK");
    }
}
