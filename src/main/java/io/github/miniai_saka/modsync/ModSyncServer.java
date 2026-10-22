package io.github.miniai_saka.modsync;

import io.github.miniai_saka.modsync.core.PackServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModSyncServer implements DedicatedServerModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("FabricModSync");
    private PackServer service;
    @Override public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                FabricLoader loader = FabricLoader.getInstance();
                service = new PackServer(loader.getGameDir(), "1.20.1",
                        loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString());
                service.start();
                LOG.info("Mod synchronization service started on port {}", service.port());
            } catch (Exception e) { LOG.error("Mod synchronization service failed to start; check config and port", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (service != null) service.close(); });
    }
}
