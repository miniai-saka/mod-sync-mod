package io.github.miniai_saka.modsync.mixin;

import io.github.miniai_saka.modsync.ModSyncServer;
import io.netty.channel.ChannelFuture;
import net.minecraft.server.ServerNetworkIo;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.net.InetAddress;
import java.util.List;

@Mixin(ServerNetworkIo.class)
abstract class ServerNetworkIoMixin {
    @Shadow @Final private List<ChannelFuture> channels;
    @Inject(method = "bind", at = @At("RETURN"))
    private void modsync$shareListener(InetAddress address, int port, CallbackInfo ci) {
        synchronized (channels) {
            for (ChannelFuture channel : channels) ModSyncServer.attach(channel.channel());
        }
    }
}
