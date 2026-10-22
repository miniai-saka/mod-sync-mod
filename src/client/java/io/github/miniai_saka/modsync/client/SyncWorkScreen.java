package io.github.miniai_saka.modsync.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncWorkScreen extends Screen {
    private final Screen parent;
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public volatile String status;
    public volatile String file = "";
    public volatile long done, total;
    public volatile int index, count;
    public volatile boolean restarting;
    private ButtonWidget cancelButton;
    public SyncWorkScreen(Screen parent, String status) { super(Text.literal("Mod同期")); this.parent = parent; this.status = status; }
    @Override protected void init() {
        cancelButton = addDrawableChild(ButtonWidget.builder(Text.literal("キャンセルしてそのまま起動"), b -> close())
                .dimensions(width / 2 - 125, height - 40, 250, 20).build());
    }
    @Override public void render(DrawContext c, int x, int y, float delta) {
        renderBackground(c);
        c.drawCenteredTextWithShadow(textRenderer, title, width / 2, 25, 0xFFFFFF);
        int top = height / 2 - 45;
        ScreenText.wrapped(c, textRenderer, status, width / 2, top, width - 40, 0xFFFFFF);
        c.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(file, width - 40), width / 2, top + 32, 0xBBBBBB);
        int barWidth = Math.min(300, width - 60), left = (width - barWidth) / 2;
        double fraction = total > 0 ? Math.min(1.0, (double) done / total) : 0;
        c.fill(left, top + 54, left + barWidth, top + 66, 0xFF303840);
        c.fill(left, top + 54, left + (int) (barWidth * fraction), top + 66, 0xFF55BB88);
        if (count > 0 || total > 0) c.drawCenteredTextWithShadow(textRenderer,
                (int) (fraction * 100) + "%  ·  " + ScreenText.size(done) + " / " + ScreenText.size(total) + "  ·  " + index + "/" + count,
                width / 2, top + 77, 0xFFFFFF);
        cancelButton.active = !restarting;
        super.render(c, x, y, delta);
    }
    @Override public boolean shouldCloseOnEsc() { return !restarting; }
    public synchronized boolean beginRestart() {
        if (cancelled.get()) return false;
        restarting = true; return true;
    }
    @Override public synchronized void close() { if (!restarting) { cancelled.set(true); client.setScreen(parent); } }
}
