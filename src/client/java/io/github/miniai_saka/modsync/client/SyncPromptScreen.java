package io.github.miniai_saka.modsync.client;

import io.github.miniai_saka.modsync.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import java.io.IOException;
import java.util.List;

public final class SyncPromptScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final SyncTransport transport;
    private final SyncSelection selection;
    private final SelectionHistory history;
    private final SelectionHistory.State remembered;
    private ModList list;
    private ButtonWidget syncButton;
    private SyncPlan selectedPlan;
    private String error = "";
    public SyncPromptScreen(Screen parent, ClientConfig config, SyncTransport transport, SyncSelection selection,
                            SelectionHistory history, SelectionHistory.State remembered) {
        super(Text.literal("同期するModを選択")); this.parent = parent; this.config = config; this.transport = transport;
        this.selection = selection; this.history = history; this.remembered = remembered;
    }
    @Override protected void init() {
        double scroll = list == null ? 0 : list.getScrollAmount();
        list = addDrawableChild(new ModList());
        list.setScrollAmount(scroll);
        int controlWidth = Math.min(340, width - 24), left = (width - controlWidth) / 2, half = (controlWidth - 6) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("すべてチェックする"), b -> { selection.selectAll(true); changed(true); })
                .dimensions(left, 76, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("すべてのチェックを外す"), b -> { selection.selectAll(false); changed(true); })
                .dimensions(left + half + 6, 76, half, 20).build());
        CheckboxWidget advanced = addDrawableChild(new CheckboxWidget(left, 101, controlWidth, 20, Text.literal("上級者向けモード"), selection.advanced()) {
            @Override public void onPress() { super.onPress(); selection.setAdvanced(isChecked()); changed(true); }
        });
        advanced.setTooltip(Tooltip.of(Text.literal("Mod IDによる旧版・新版の連動と重複防止を解除します。同名ファイルの上書きに必要なバックアップは常に連動します。")));
        syncButton = addDrawableChild(ButtonWidget.builder(Text.literal("選択したModを同期して再起動する"), b -> sync())
                .dimensions(width / 2 - 155, height - 47, 310, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("しないでそのまま起動する"), b -> close())
                .dimensions(width / 2 - 155, height - 24, 310, 20).build());
        changed(false);
    }
    private void changed(boolean persist) {
        try {
            selectedPlan = selection.plan();
            syncButton.active = selectedPlan.changed();
            if (persist) { selection.remember(remembered); history.save(SelectionHistory.path(ModSyncClient.GAME)); }
            error = "";
        } catch (IOException e) { syncButton.active = false; error = e.getMessage(); }
    }
    private void sync() {
        final SyncPlan plan;
        try {
            plan = selection.plan();
            if (!plan.changed()) { client.setScreen(new ResultScreen(this, "選択した項目に同期が必要な変更はありません。", true)); return; }
            selection.remember(remembered); history.save(SelectionHistory.path(ModSyncClient.GAME));
        } catch (IOException e) { error = e.getMessage(); return; }
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
        c.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFCC77);
        ScreenText.wrapped(c, textRenderer, "サーバー側のModとこのクライアントのModに不整合があります。同期しますか？", width / 2, 23, Math.min(570, width - 24), 0xFFFFFF);
        c.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(config.server, width - 24), width / 2, 47, 0x88CCFF);
        try {
            SyncPlan plan = selectedPlan;
            if (plan != null)
            c.drawCenteredTextWithShadow(textRenderer, "導入: " + plan.downloads().size() + "個 (" + ScreenText.size(plan.downloadBytes())
                    + ")  /  退避: " + plan.removals().size() + "個", width / 2, 61, 0xDDDDDD);
        } catch (RuntimeException ignored) {}
        String hint = !error.isEmpty() ? error : !selection.notice().isEmpty() ? selection.notice()
                : selectedPlan != null && !selectedPlan.changed() ? "同期する項目が選択されていません。チェックを付けると同期できます。"
                : selection.advanced() ? "上級者向けモード: Mod IDの競合チェックを解除中" : "チェックを外したModは残します。選択は同期先ごとに記憶します。";
        ScreenText.wrapped(c, textRenderer, hint, width / 2, height - 74, width - 28,
                !error.isEmpty() || selection.advanced() ? 0xFFAA77 : 0xBBBBBB);
        super.render(c, x, y, delta);
    }
    @Override public void close() {
        try { selection.remember(remembered); history.save(SelectionHistory.path(ModSyncClient.GAME)); client.setScreen(parent); }
        catch (IOException e) { client.setScreen(new ResultScreen(parent, "選択履歴を保存できませんでした。\n" + e.getMessage())); }
    }
    private final class ModList extends ElementListWidget<ModRow> {
        ModList() {
            super(SyncPromptScreen.this.client, SyncPromptScreen.this.width, SyncPromptScreen.this.height, 126,
                    Math.max(146, SyncPromptScreen.this.height - 80), 36);
            setRenderBackground(false); setRenderHorizontalShadows(false);
            selection.items().forEach(item -> addEntry(new ModRow(item)));
        }
        @Override public int getRowWidth() { return Math.min(650, width - 32); }
        @Override protected int getScrollbarPositionX() { return width / 2 + getRowWidth() / 2 + 3; }
    }
    private final class ModRow extends ElementListWidget.Entry<ModRow> {
        private final SyncSelection.Item item;
        private final BoundCheckbox checkbox;
        ModRow(SyncSelection.Item item) {
            this.item = item;
            checkbox = new BoundCheckbox(item);
            checkbox.setTooltip(Tooltip.of(Text.literal((item.action() == SyncSelection.Action.INSTALL ? "サーバーから導入: " : "現在のModを退避: ")
                    + item.file() + "\nMod ID: " + item.details().id() + "\nバージョン: " + item.details().version())));
        }
        @Override public List<? extends Element> children() { return List.of(checkbox); }
        @Override public List<? extends Selectable> selectableChildren() { return List.of(checkbox); }
        @Override public void render(DrawContext c, int index, int y, int x, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float delta) {
            checkbox.synchronize(); checkbox.setX(x); checkbox.setY(y); checkbox.setWidth(rowWidth - 8);
            String action = item.action() == SyncSelection.Action.INSTALL ? "導入: " : "退避: ";
            checkbox.setMessage(Text.literal(textRenderer.trimToWidth(action + item.file(), rowWidth - 34)));
            checkbox.render(c, mouseX, mouseY, delta);
            String tag = switch (item.details().environment()) {
                case SERVER -> "サーバー専用";
                case CLIENT -> "クライアント専用";
                case BOTH -> "クライアント・サーバー双方";
                case UNKNOWN -> "用途未判定";
            };
            int color = switch (item.details().environment()) {
                case SERVER -> 0xFFAD66;
                case CLIENT -> 0x88DDAA;
                case BOTH -> 0x88BBFF;
                case UNKNOWN -> 0xAAAAAA;
            };
            String detail = "[" + tag + "] " + item.details().id() + (item.details().version().isEmpty() ? "" : "  " + item.details().version());
            c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(detail, rowWidth - 30), x + 24, y + 23, color);
        }
    }
    private final class BoundCheckbox extends CheckboxWidget {
        private final SyncSelection.Item item;
        BoundCheckbox(SyncSelection.Item item) { super(0, 0, 200, 20, Text.empty(), item.selected()); this.item = item; }
        @Override public void onPress() { super.onPress(); selection.setSelected(item, isChecked()); changed(true); }
        void synchronize() { if (isChecked() != item.selected()) super.onPress(); }
    }
}
