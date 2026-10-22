package io.github.miniai_saka.modsync.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** No Fabric/Minecraft classes: the embedded helper can replace even the synchronization mod itself. */
public final class HelperMain {
    public record Request(String game, String stage, long parentPid, List<String> command, String workingDirectory) {}
    private HelperMain() {}
    public static void main(String[] args) {
        Path game = null;
        try {
            if (args.length == 2 && args[0].equals("serve")) {
                game = Path.of(args[1]).toAbsolutePath().normalize();
                try (PackServer server = new PackServer(game, "1.20.1", "0.19.5")) {
                    server.start(); System.out.println("PORT=" + server.port());
                    new java.util.concurrent.CountDownLatch(1).await();
                }
                return;
            }
            if (args.length == 3 && args[0].equals("apply")) {
                game = Path.of(args[1]).toAbsolutePath().normalize();
                Transaction.apply(game, Path.of(args[2])); return;
            }
            if (args.length != 1 || !args[0].equals("update")) throw new IOException("Use update (stdin), apply <game> <stage>, or serve <server>");
            Request r = Json.GSON.fromJson(new InputStreamReader(System.in, StandardCharsets.UTF_8), Request.class);
            if (r == null || r.command() == null || r.command().isEmpty()) throw new IOException("No restart command");
            game = Path.of(r.game()).toAbsolutePath().normalize();
            Path stage = Transaction.checkedStage(game, Path.of(r.stage()));
            if (r.parentPid() < 1 || r.parentPid() == ProcessHandle.current().pid()) throw new IOException("Invalid parent process");
            // Acknowledge only after the complete request is received and validated.
            System.out.println("READY"); System.out.flush();
            Optional<ProcessHandle> parent = ProcessHandle.of(r.parentPid());
            if (parent.isPresent()) parent.get().onExit().get(120, TimeUnit.SECONDS);
            Thread.sleep(1200); // allow Windows native libraries and Jar handles to close
            Transaction.apply(game, stage);
            ProcessBuilder restart = new ProcessBuilder(r.command()).directory(Path.of(r.workingDirectory()).toFile());
            Path logs = game.resolve("logs"); Files.createDirectories(logs);
            restart.redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("mod-sync-restarted-client.log").toFile()))
                    .redirectErrorStream(true);
            Process next = restart.start(); next.getOutputStream().close();
            log(game, "Synchronization committed; restarted client PID " + next.pid());
        } catch (Exception e) {
            if (game != null) {
                try {
                    Json.write(game.resolve(".modsync/last-result.json"), Map.of("success", false, "error", Objects.toString(e.getMessage(), "Unknown error")));
                    log(game, "ERROR: " + e);
                } catch (IOException ignored) {}
            }
            // Never print launch arguments: they can contain an authentication token.
            System.err.println("Mod synchronization failed: " + e.getMessage());
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null,
                        "Modの同期または再起動に失敗しました。\nバックアップと logs/mod-sync-updater.log を確認してください。\n" + e.getMessage(),
                        "Fabric Mod Sync", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
        }
    }
    private static void log(Path game, String message) throws IOException {
        Files.createDirectories(game.resolve("logs"));
        Files.writeString(game.resolve("logs/mod-sync-updater.log"), Instant.now() + " " + message + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
