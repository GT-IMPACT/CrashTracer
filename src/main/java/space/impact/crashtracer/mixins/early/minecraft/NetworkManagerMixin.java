package space.impact.crashtracer.mixins.early.minecraft;

import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import space.impact.crashtracer.diagnostics.InteractionCapture;
import space.impact.crashtracer.diagnostics.PlayerInteractionState;

@Mixin(NetworkManager.class)
public abstract class NetworkManagerMixin {
    @Redirect(method = {"processReceivedPackets", "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Packet;processPacket(Lnet/minecraft/network/INetHandler;)V"), require = 2)
    private void crashtracer$packet(Packet packet, INetHandler handler) throws Throwable {
        PlayerInteractionState before = null;
        try { before = InteractionCapture.before(packet, handler); } catch (Throwable unavailable) { }
        boolean entered = false;
        try { entered = InteractionCapture.enter(packet, handler, before); } catch (Throwable unavailable) { }
        try {
            packet.processPacket(handler);
        } catch (Throwable failure) {
            try { InteractionCapture.failed(packet, handler, before, failure); } catch (Throwable unavailable) { }
            throw failure;
        } finally {
            if (entered) try { InteractionCapture.leave(); } catch (Throwable unavailable) { }
        }
    }
}
