package io.github.miniai_saka.modsync.core;

import java.awt.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Optional JVM agent for packs that cannot reach Minecraft's title screen due to missing dependencies. */
public final class PrelaunchAgent {
    private PrelaunchAgent() {}
    public static void premain(String gameDirectory, Instrumentation ignored) {
        try {
            if (gameDirectory == null || gameDirectory.isBlank()) throw new java.io.IOException("javaagentにゲームディレクトリを指定してください。");
            Path game = Path.of(gameDirectory).toAbsolutePath().normalize();
            ClientConfig config = ClientConfig.load(game.resolve("config/fabric-mod-sync-client.json"));
            if (!config.enabled) return;
            System.setProperty("fabric.modsync.prechecked", "true");
            SyncTransport transport = new SyncTransport(config);
            Manifest manifest = transport.manifest();
            String loader = loaderVersion();
            if (manifest.enabled() && !manifest.loader().equals(loader)) throw new java.io.IOException("Fabric Loaderをサーバーと一致させてください。必要なバージョン: " + manifest.loader());
            SyncPlan candidates = SyncPlan.create(manifest, Inventory.scan(game.resolve("mods")));
            if (!manifest.enabled() || !candidates.changed()) return;
            SelectionHistory history = SelectionHistory.load(SelectionHistory.path(game));
            SelectionHistory.State remembered = history.server(config);
            SyncSelection selection = new SyncSelection(candidates, ModDetails.scan(game.resolve("mods")), remembered);
            if (!selection.plan().changed()) return;
            if (GraphicsEnvironment.isHeadless()) throw new java.io.IOException("同期への同意画面を表示できません。");
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            SyncPlan[] selected = new SyncPlan[1];
            Exception[] selectionError = new Exception[1];
            SwingUtilities.invokeAndWait(() -> {
                try { selected[0] = PrelaunchSelectionDialog.choose(selection, config, history, remembered, game); }
                catch (Exception e) { selectionError[0] = e; }
            });
            if (selectionError[0] != null) throw selectionError[0];
            SyncPlan plan = selected[0];
            if (plan == null) return;
            JDialog[] dialog = new JDialog[1]; JProgressBar[] bar = new JProgressBar[1]; JLabel[] label = new JLabel[1];
            AtomicBoolean cancel = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> {
                dialog[0] = new JDialog((Frame) null, "Modを同期しています", false);
                dialog[0].setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                JPanel panel = new JPanel(new BorderLayout(10, 10)); panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
                label[0] = new JLabel("ダウンロードを準備しています…"); bar[0] = new JProgressBar(0, 1000); bar[0].setStringPainted(true);
                JButton button = new JButton("キャンセル"); button.addActionListener(e -> cancel.set(true));
                panel.add(label[0], BorderLayout.NORTH); panel.add(bar[0], BorderLayout.CENTER); panel.add(button, BorderLayout.SOUTH);
                dialog[0].setContentPane(panel); dialog[0].setSize(540, 160); dialog[0].setLocationRelativeTo(null); dialog[0].setVisible(true);
            });
            try {
                Path stage = transport.stage(game, plan, (file, index, count, done, total) -> SwingUtilities.invokeLater(() -> {
                    label[0].setText(index + "/" + count + " " + file);
                    bar[0].setValue(total == 0 ? 1000 : (int) (done * 1000 / total));
                }), cancel);
                if (!cancel.get()) Transaction.apply(game, stage);
            } finally { SwingUtilities.invokeAndWait(() -> dialog[0].dispose()); }
        } catch (Exception e) {
            System.err.println("Fabric Mod Sync prelaunch: " + e.getMessage());
            if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null,
                    "起動前のMod同期に失敗しました。\n" + e.getMessage() + "\n現在のMod構成で起動を続けます。", "Fabric Mod Sync", JOptionPane.WARNING_MESSAGE);
        }
    }
    private static String loaderVersion() throws java.io.IOException {
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String name = Path.of(entry).getFileName().toString();
            if (name.matches("fabric-loader-[0-9].*\\.jar")) return name.substring("fabric-loader-".length(), name.length() - 4);
        }
        throw new java.io.IOException("起動クラスパスからFabric Loaderのバージョンを確認できませんでした。");
    }
}
