package io.github.miniai_saka.modsync.client;

import io.github.miniai_saka.modsync.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class SyncPromptScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final SyncTransport transport;
    private final SyncPlan plan;
    public SyncPromptScreen(Screen parent, ClientConfig config, SyncTransport transport, SyncPlan plan) {
        super(Text.literal("Mod構成の不整合")); this.parent = parent; this.config = config; this.transport = transport; this.plan = plan;
    }
    @Override protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("同期して再起動する"), b -> sync()).dimensions(width / 2 - 155, height - 53, 310, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("しないでそのまま起動する"), b -> close()).dimensions(width / 2 - 155, height - 28, 310, 20).build());
    }
    private void sync() {
        SyncWorkScreen progress = new SyncWorkScreen(parent, "変更されたModをダウンロードしています…");
        client.setScreen(progress);
        ModSyncClient.WORKER.execute(() -> {
            try {
                // Validate restart support before downloading or altering anything.
                RestartCoordinator.Command restart = RestartCoordinator.capture();
                java.nio.file.Path stage = transport.stage(ModSyncClient.GAME, plan, (file, index, count, done, total) -> {
                    progress.file = file; progress.index = index; progress.count = count; progress.done = done; progress.total = total;
                }, progress.cancelled);
                if (progress.cancelled.get()) return;
                RestartCoordinator.validateSyncMod(plan, stage);
                if (!progress.beginRestart()) return;
                progress.status = "照合完了。Modを入れ替えて再起動します…";
                RestartCoordinator.start(stage, restart);
                client.execute(client::scheduleStop);
            } catch (Exception e) {
                ModSyncClient.LOG.warn("Synchronization stopped: {}", e.getMessage());
                client.execute(() -> { if (!progress.cancelled.get()) client.setScreen(new ResultScreen(parent, e.getMessage())); });
            }
        });
    }
    @Override public void render(DrawContext c, int x, int y, float delta) {
        renderBackground(c);
        c.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFCC77);
        int at = ScreenText.wrapped(c, textRenderer, "サーバー側のModとこのクライアントのModに不整合があります。同期しますか？", width / 2, 40, Math.min(390, width - 40), 0xFFFFFF);
        at = ScreenText.wrapped(c, textRenderer, config.server, width / 2, at + 7, width - 40, 0x88CCFF);
        at = ScreenText.wrapped(c, textRenderer, "取得・更新: " + plan.downloads().size() + "個 (" + ScreenText.size(plan.downloadBytes()) + ") / 余分なModの退避: " + plan.removals().size() + "個", width / 2, at + 7, width - 30, 0xDDDDDD);
        at = ScreenText.wrapped(c, textRenderer, "同期後はサーバー指定のMod構成に完全一致します。退避したModはバックアップに保存されます。", width / 2, at + 7, width - 40, 0xBBBBBB);
        int available = Math.max(0, (height - 62 - at) / 11);
        int shown = 0;
        for (var e : plan.downloads()) { if (shown++ >= available) break; c.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth("↓ " + e.name(), width - 40), width / 2, at, 0x88DDAA); at += 11; }
        if (shown < available) for (String name : plan.removals()) { if (shown++ >= available) break; c.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth("退避: " + name, width - 40), width / 2, at, 0xFFAA88); at += 11; }
        super.render(c, x, y, delta);
    }
    @Override public void close() { client.setScreen(parent); }
}
