package space.impact.crashtracer.smoke;

import java.io.File;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ReportedException;
import net.minecraftforge.common.util.FakePlayerFactory;
import space.impact.crashtracer.bootstrap.TracerBootstrap;
import space.impact.crashtracer.diagnostics.*;

/** Exercises actual NetworkManager dispatch, including a report constructed before handler unwinding. */
final class PacketContextSmoke {
    static void run() {
        MinecraftServer server = MinecraftServer.getServer();
        EntityPlayerMP player = FakePlayerFactory.getMinecraft(server.worldServerForDimension(0));
        player.inventory.currentItem = 2;
        player.inventory.mainInventory[2] = new ItemStack(Items.stick, 1, 0);
        player.setSneaking(true);
        for (final boolean wrapped : new boolean[] {false, true}) {
            player.openContainer = player.inventoryContainer;
            NetworkManager manager = new NetworkManager(false);
            EmbeddedChannel channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            final NullPointerException expected = new NullPointerException("CRASHTRACER_PACKET_CONTEXT");
            new NetHandlerPlayServer(server, manager, player) {
                @Override public void processPlayerBlockPlacement(C08PacketPlayerBlockPlacement packet) {
                    player.openContainer = new Container() {
                        @Override public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer ignored) { return true; }
                    };
                    player.openContainer.windowId = 9;
                    if (wrapped) throw new ReportedException(new CrashReport("Ticking memory connection", expected));
                    throw expected;
                }
            };
            C08PacketPlayerBlockPlacement packet = new C08PacketPlayerBlockPlacement();
            net.minecraft.network.PacketBuffer buffer = new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
            try {
                buffer.writeInt(-1); buffer.writeByte(255); buffer.writeInt(-1); buffer.writeByte(255);
                buffer.writeItemStackToBuffer(player.getHeldItem());
                buffer.writeByte(0); buffer.writeByte(0); buffer.writeByte(0);
                packet.readPacketData(buffer);
            } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
            finally { buffer.release(); }
            channel.writeInbound(packet);
            try { manager.processReceivedPackets(); throw new AssertionError("Expected packet failure"); }
            catch (RuntimeException failure) {
                if (wrapped ? !(failure instanceof ReportedException) : failure != expected) throw new AssertionError("Original exception changed", failure);
                CrashReport report = wrapped ? ((ReportedException) failure).getCrashReport() : new CrashReport("Ticking memory connection", failure);
                report.saveToFile(new File("crash-reports/crashtracer-packet-" + wrapped + ".txt"));
                File incident = TracerBootstrap.incidentFor(expected);
                IncidentReport saved = PendingAnalysis.INSTANCE.readReport(new File(incident, "report.json"), 16L * 1024 * 1024);
                InteractionSnapshot interaction = saved.getInteraction();
                if (interaction == null || !"RIGHT_CLICK_AIR".equals(interaction.getAction())
                    || interaction.getBefore().getContainer().getMatchingSlotNumber() == null
                    || interaction.getAtFailure().getContainer().getMatchingSlotNumber() != null
                    || interaction.getAtFailure().getContainer().getWindowId() != 9
                    || !"minecraft:stick".equals(interaction.getBefore().getHeldItem().getRegistryName()))
                    throw new AssertionError("Packet context incomplete");
                System.out.println("CRASHTRACER_PACKET_CONTEXT_PASS wrapped=" + wrapped + " incident=" + incident.getName());
            } finally { channel.finish(); }
        }
        CrashReport unrelated = new CrashReport("Unrelated server report", new IllegalStateException("UNRELATED"));
        File incident = TracerBootstrap.incidentFor(unrelated.getCrashCause());
        IncidentReport saved = PendingAnalysis.INSTANCE.readReport(new File(incident, "report.json"), 16L * 1024 * 1024);
        if (saved.getInteraction() != null) throw new AssertionError("Previous packet leaked into unrelated report");
        System.out.println("CRASHTRACER_PACKET_CONTEXT_NO_STALE_STATE");
    }
}
