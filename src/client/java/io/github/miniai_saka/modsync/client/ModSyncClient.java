package io.github.miniai_saka.modsync.client;

import io.github.miniai_saka.modsync.core.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class ModSyncClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("FabricModSync");
    public static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mod-sync-client"); t.setDaemon(true); return t;
    });
    public static final Path GAME = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    public static final Path CONFIG = GAME.resolve("config/fabric-mod-sync-client.json");
    private boolean startupChecked;
    @Override public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof OptionsScreen) Screens.getButtons(screen).add(
                    ButtonWidget.builder(Text.literal("Mod同期設定"), b -> client.setScreen(new SyncSettingsScreen(screen)))
                            .dimensions(width - 109, 5, 104, 20).build());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!startupChecked && client.currentScreen instanceof TitleScreen) {
                startupChecked = true;
                if (Boolean.getBoolean("fabric.modsync.prechecked")) return;
                try {
                    ClientConfig config = ClientConfig.load(CONFIG);
                    if (!Files.exists(CONFIG)) Json.write(CONFIG, config);
                    if (config.enabled) check(client.currentScreen, config);
                } catch (Exception e) { LOG.error("Could not load mod sync config", e); }
            }
        });
    }
    public static void check(Screen parent, ClientConfig config) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            client.setScreen(new ResultScreen(parent, "タイトル画面から同期してください。ワールドを終了してから利用できます。")); return;
        }
        SyncWorkScreen work = new SyncWorkScreen(parent, "サーバーのMod一覧を確認しています…");
        client.setScreen(work);
        WORKER.execute(() -> {
            try {
                SyncTransport transport = new SyncTransport(config);
                Manifest manifest = transport.manifest();
                String loader = FabricLoader.getInstance().getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString();
                if (manifest.enabled() && !loader.equals(manifest.loader()))
                    throw new java.io.IOException("Fabric Loaderのバージョンが異なります。サーバー: " + manifest.loader() + " / クライアント: " + loader + "。ランチャーで同じバージョンに変更してください。");
                SyncPlan plan = SyncPlan.create(manifest, Inventory.scan(GAME.resolve("mods")));
                client.execute(() -> {
                    if (work.cancelled.get() || client.currentScreen != work) return;
                    if (!manifest.enabled()) client.setScreen(new ResultScreen(parent, "サーバー側で同期が無効になっています。"));
                    else if (!plan.changed()) client.setScreen(parent);
                    else client.setScreen(new SyncPromptScreen(parent, config, transport, plan));
                });
            } catch (Exception e) {
                LOG.warn("Mod list check failed: {}", e.getMessage());
                client.execute(() -> { if (!work.cancelled.get() && client.currentScreen == work) client.setScreen(new ResultScreen(parent, e.getMessage())); });
            }
        });
    }
}
