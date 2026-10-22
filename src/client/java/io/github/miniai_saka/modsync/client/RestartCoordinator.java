package io.github.miniai_saka.modsync.client;

import io.github.miniai_saka.modsync.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

final class RestartCoordinator {
    record Command(List<String> arguments, String directory) {}
    private RestartCoordinator() {}
    static Command capture() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElseThrow(() -> new IOException("Javaの再起動コマンドを取得できませんでした。通常のランチャーから起動してください。"));
        List<String> command = new ArrayList<>();
        if (info.arguments().isPresent()) { command.add(executable); command.addAll(Arrays.asList(info.arguments().get())); }
        else if (System.getProperty("os.name").startsWith("Windows")) { command.addAll(WindowsArguments.current()); command.set(0, executable); }
        else throw new IOException("起動引数を取得できませんでした。このランチャーからは自動再起動できません。");
        if (command.size() < 2) throw new IOException("再起動の起動引数がありません。");
        // Launcher-owned native extraction directories may be deleted when the first game exits.
        // Preserve native binaries and point the restarted JVM at a private persistent copy.
        Path natives = ModSyncClient.GAME.resolve(".modsync/runtime").resolve(UUID.randomUUID().toString()).resolve("natives");
        SafePaths.noLinks(natives);
        Map<String, String> properties = Map.of("-Djava.library.path=", System.getProperty("java.library.path", ""),
                "-Dorg.lwjgl.librarypath=", System.getProperty("org.lwjgl.librarypath", ""));
        for (var property : properties.entrySet()) {
            if (property.getValue().isEmpty()) continue;
            if (command.stream().noneMatch(arg -> arg.startsWith(property.getKey()))) continue;
            boolean copied = false;
            for (String directory : property.getValue().split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path source;
                try { source = Path.of(directory); } catch (RuntimeException e) { continue; }
                if (!Files.isDirectory(source)) continue;
                try (var files = Files.list(source)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (Files.isRegularFile(file) && (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib"))) {
                            Files.createDirectories(natives);
                            Files.copy(file, natives.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING); copied = true;
                        }
                    }
                }
            }
            if (copied) for (int i = 1; i < command.size(); i++) if (command.get(i).startsWith(property.getKey())) command.set(i, property.getKey() + natives);
        }
        // Expand JVM @argument files while the original launcher still owns them.
        for (int i = 1; i < command.size(); i++) {
            if (command.get(i).startsWith("@")) throw new IOException("@引数ファイル方式のランチャーは自動再起動に未対応です。通常のMinecraftランチャーを利用してください。");
        }
        return new Command(List.copyOf(command), Path.of(System.getProperty("user.dir")).toAbsolutePath().toString());
    }
    static void validateSyncMod(SyncPlan plan, Path stage) throws IOException {
        PackValidation.requireSyncMod(ModSyncClient.GAME, stage, plan);
    }
    static void start(Path stage, Command restart) throws Exception {
        Path helper = ModSyncClient.GAME.resolve(".modsync/helper-" + UUID.randomUUID() + ".jar");
        SafePaths.noLinks(helper);
        try (InputStream input = RestartCoordinator.class.getResourceAsStream("/modsync-helper.jar")) {
            if (input == null) throw new IOException("再起動ヘルパーが見つかりません。");
            Files.copy(input, helper);
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java");
        Files.createDirectories(ModSyncClient.GAME.resolve("logs"));
        Process child = new ProcessBuilder(java.toString(), "-jar", helper.toString(), "update")
                .redirectError(ProcessBuilder.Redirect.appendTo(ModSyncClient.GAME.resolve("logs/mod-sync-updater.log").toFile())).start();
        try {
            HelperMain.Request request = new HelperMain.Request(ModSyncClient.GAME.toString(), stage.toString(), ProcessHandle.current().pid(), restart.arguments(), restart.directory());
            try (var writer = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8)) { Json.GSON.toJson(request, writer); }
            String ready = CompletableFuture.supplyAsync(() -> {
                try { return new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)).readLine(); }
                catch (IOException e) { throw new CompletionException(e); }
            }).get(10, TimeUnit.SECONDS);
            if (!"READY".equals(ready) || !child.isAlive()) throw new IOException("再起動ヘルパーを起動できませんでした。Minecraftは終了しません。");
        } catch (Exception e) { child.destroyForcibly(); throw e; }
    }
}
