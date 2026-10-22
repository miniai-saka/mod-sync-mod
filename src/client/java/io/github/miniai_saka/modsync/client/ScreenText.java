package io.github.miniai_saka.modsync.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

final class ScreenText {
    private ScreenText() {}
    static int wrapped(DrawContext c, TextRenderer font, String text, int center, int y, int maxWidth, int color) {
        for (var line : font.wrapLines(Text.literal(text == null ? "不明なエラー" : text), maxWidth)) {
            c.drawCenteredTextWithShadow(font, line, center, y, color); y += 12;
        }
        return y;
    }
    static String size(long bytes) { return String.format(java.util.Locale.ROOT, "%.2f MiB", bytes / 1048576.0); }
}
