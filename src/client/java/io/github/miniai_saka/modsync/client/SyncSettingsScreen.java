package io.github.miniai_saka.modsync.client;

import io.github.miniai_saka.modsync.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;

public final class SyncSettingsScreen extends Screen {
    private final Screen parent;
    private ClientConfig config;
    private TextFieldWidget address, token;
    private boolean enabled;
    private String error = "";
    public SyncSettingsScreen(Screen parent) {
        super(Text.literal("Mod同期設定")); this.parent = parent;
        try { config = ClientConfig.load(ModSyncClient.CONFIG); }
        catch (Exception e) { config = new ClientConfig(); error = e.getMessage(); }
        enabled = config.enabled;
    }
    @Override protected void init() {
        String previousAddress = address == null ? config.server : address.getText();
        String previousToken = token == null ? config.accessToken : token.getText();
        int left = width / 2 - 150, top = Math.max(36, height / 2 - 85);
        addDrawableChild(ButtonWidget.builder(toggleText(), b -> { enabled = !enabled; b.setMessage(toggleText()); })
                .dimensions(left, top, 300, 20).build());
        address = new TextFieldWidget(textRenderer, left, top + 43, 300, 20, Text.literal("同期サーバーのドメイン:ポート"));
        address.setMaxLength(512); address.setText(previousAddress); addDrawableChild(address);
        token = new TextFieldWidget(textRenderer, left, top + 86, 300, 20, Text.literal("アクセストークン（任意）"));
        token.setMaxLength(256); token.setText(previousToken);
        token.setRenderTextProvider((text, first) -> Text.literal("•".repeat(text.length())).asOrderedText());
        addDrawableChild(token);
        ButtonWidget check = addDrawableChild(ButtonWidget.builder(Text.literal("保存して今すぐ確認"), b -> { if (save()) ModSyncClient.check(parent, config); })
                .dimensions(left, top + 120, 300, 20).build());
        check.active = client.world == null;
        addDrawableChild(ButtonWidget.builder(Text.literal("保存して戻る"), b -> { if (save()) close(); }).dimensions(left, height - 30, 145, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("キャンセル"), b -> close()).dimensions(left + 155, height - 30, 145, 20).build());
        setInitialFocus(address);
    }
    private Text toggleText() { return Text.literal("起動時の同期: " + (enabled ? "有効" : "無効")); }
    private boolean save() {
        try {
            ClientConfig.endpoint(address.getText());
            config.enabled = enabled; config.server = address.getText().strip(); config.accessToken = token.getText();
            SafePaths.noLinks(ModSyncClient.CONFIG);
            Json.write(ModSyncClient.CONFIG, config); error = ""; return true;
        } catch (Exception e) { error = e.getMessage(); return false; }
    }
    @Override public void render(DrawContext c, int x, int y, float delta) {
        renderBackground(c);
        c.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFF);
        int left = width / 2 - 150, top = Math.max(36, height / 2 - 85);
        c.drawTextWithShadow(textRenderer, Text.literal("同期サーバー (例: example.com:25566)"), left, top + 30, 0xCCCCCC);
        c.drawTextWithShadow(textRenderer, Text.literal("アクセストークン（サーバー設定時のみ）"), left, top + 73, 0xCCCCCC);
        if (!error.isEmpty()) ScreenText.wrapped(c, textRenderer, error, width / 2, top + 145, width - 40, 0xFF7777);
        super.render(c, x, y, delta);
    }
    @Override public void close() { client.setScreen(parent); }
}
