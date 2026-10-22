package io.github.miniai_saka.modsync.core;

public final class RestartProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("wait")) { System.out.println("WAITING"); System.out.flush(); System.in.read(); }
        else java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), args[0].equals("preflight") ? System.getProperty("fabric.modsync.prechecked", "false") : "restarted");
    }
}
