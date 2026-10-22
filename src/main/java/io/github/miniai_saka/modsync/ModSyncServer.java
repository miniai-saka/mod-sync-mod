package io.github.miniai_saka.modsync;

import io.github.miniai_saka.modsync.core.PackService;
import io.github.miniai_saka.modsync.network.PortSharing;
import io.netty.channel.Channel;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModSyncServer implements DedicatedServerModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("FabricModSync");
    private static volatile PackService service;
    private static PortSharing shared;
    public static void attach(Channel listener) { if (shared != null) shared.attach(listener); }
    @Override public void onInitializeServer() {
        shared = new PortSharing(() -> service);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                FabricLoader loader = FabricLoader.getInstance();
                service = new PackService(loader.getGameDir(), "1.20.1",
                        loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString());
                LOG.info("Mod synchronization shares Minecraft TCP port {} (HTTP /modsync/v1/)", server.getServerPort());
            } catch (Exception e) { LOG.error("Mod synchronization service failed to start; check config and port", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { service = null; if (shared != null) shared.close(); });
    }
}
