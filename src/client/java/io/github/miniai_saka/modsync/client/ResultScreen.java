package io.github.miniai_saka.modsync.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class ResultScreen extends Screen {
    private final Screen parent;
    private final String message;
    public ResultScreen(Screen parent, String message) { super(Text.literal("Mod同期")); this.parent = parent; this.message = message; }
    @Override protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("戻る"), b -> close()).dimensions(width / 2 - 100, height - 38, 200, 20).build());
    }
    @Override public void render(DrawContext c, int x, int y, float delta) {
        renderBackground(c);
        c.drawCenteredTextWithShadow(textRenderer, title, width / 2, 25, 0xFFFFFF);
        ScreenText.wrapped(c, textRenderer, message, width / 2, 65, width - 48, 0xFFCC99);
        super.render(c, x, y, delta);
    }
    @Override public void close() { client.setScreen(parent); }
}
