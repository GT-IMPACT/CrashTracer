package space.impact.crashtracer.diagnostics

import net.minecraft.network.INetHandler
import net.minecraft.network.NetHandlerPlayServer
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.inventory.Slot
import space.impact.crashtracer.bootstrap.TracerBootstrap
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Read on the packet's processing thread; retained records contain no player/container/packet references. */
object InteractionCapture {
    private data class Failure(val error: WeakReference<Throwable>, val snapshot: InteractionSnapshot)
    private val failures = ArrayDeque<Failure>()
    private data class Active(val packet: Packet, val handler: INetHandler, val before: PlayerInteractionState?)
    private val active = ThreadLocal<ArrayDeque<Active>>()

    @JvmStatic fun enter(packet: Packet, handler: INetHandler, before: PlayerInteractionState?): Boolean {
        if (handler !is NetHandlerPlayServer || !TracerBootstrap.getOptions().enabled) return false
        val stack = active.get() ?: ArrayDeque<Active>().also(active::set)
        if (stack.size >= 8) return false
        stack.addLast(Active(packet, handler, before))
        return true
    }

    @JvmStatic fun leave() {
        active.get()?.let { stack ->
            if (stack.isNotEmpty()) stack.removeLast()
            if (stack.isEmpty()) active.remove()
        }
    }

    @JvmStatic fun before(packet: Packet, handler: INetHandler): PlayerInteractionState? {
        if (packet !is C08PacketPlayerBlockPlacement || handler !is NetHandlerPlayServer || !TracerBootstrap.getOptions().enabled) return null
        return player(handler)
    }

    @JvmStatic fun failed(packet: Packet, handler: INetHandler, before: PlayerInteractionState?, error: Throwable) {
        if (handler !is NetHandlerPlayServer || TracerBootstrap.getSessionDirectory() == null) return
        val snapshot = snapshot(packet, handler, before)
        synchronized(failures) {
            failures.removeAll { it.error.get() == null }
            if (failures.none { it.error.get() === error }) failures.addLast(Failure(WeakReference(error), snapshot))
            while (failures.size > 32) failures.removeFirst()
        }
    }

    private fun snapshot(packet: Packet, handler: NetHandlerPlayServer, before: PlayerInteractionState?): InteractionSnapshot {
        val limits = mutableListOf("Packet contents are allowlisted; NBT, chat, commands and remote addresses are not captured.")
        val fields = linkedMapOf<String, String>()
        var action: String? = null
        var packetItem: ItemSnapshot? = null
        if (packet is C08PacketPlayerBlockPlacement) {
            action = if (packet.func_149568_f() == 255) "RIGHT_CLICK_AIR" else "RIGHT_CLICK_BLOCK"
            fields["x"] = packet.func_149576_c().toString()
            fields["y"] = packet.func_149571_d().toString()
            fields["z"] = packet.func_149570_e().toString()
            fields["face"] = packet.func_149568_f().toString()
            fields["hitX"] = packet.func_149573_h().toString()
            fields["hitY"] = packet.func_149569_i().toString()
            fields["hitZ"] = packet.func_149575_j().toString()
            packetItem = item(packet.func_149574_g())
        } else limits.add("Only packet class is recorded for this packet type; no pre-processing player snapshot.")
        val after = try { player(handler) } catch (failure: Throwable) {
            limits.add("Player state unavailable: ${failure.javaClass.name}"); null
        }
        if (before == null && packet is C08PacketPlayerBlockPlacement) limits.add("Pre-processing player state unavailable.")
        return InteractionSnapshot(System.currentTimeMillis(), packet.javaClass.name.take(256), action,
            fields, packetItem, before, after, limits)
    }

    /** Exact throwable identity, including wrappers; never attaches the last unrelated packet. */
    fun forError(error: Throwable): InteractionSnapshot? {
        val queue = ArrayDeque<Throwable>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        queue.add(error)
        var examined = 0
        synchronized(failures) {
            while (queue.isNotEmpty() && examined++ < 64) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue
                failures.firstOrNull { it.error.get() === current }?.let { return it.snapshot }
                try { current.cause?.let(queue::addLast) } catch (_: Throwable) { }
                try { current.suppressed.take(16).forEach(queue::addLast) } catch (_: Throwable) { }
            }
        }
        // A ReportedException may construct its CrashReport before unwinding out of the packet handler.
        val processing = active.get()?.peekLast() ?: return null
        val handler = processing.handler as? NetHandlerPlayServer ?: return null
        return snapshot(processing.packet, handler, processing.before)
    }

    private fun player(handler: NetHandlerPlayServer): PlayerInteractionState {
        val player = handler.playerEntity
        val selected = player.inventory.currentItem
        val container = player.openContainer
        val slots = container?.inventorySlots
        val match = slots?.take(512)?.filterIsInstance<Slot>()?.firstOrNull {
            it.inventory === player.inventory && it.slotIndex == selected
        }
        return PlayerInteractionState(player.dimension, player.isSneaking, item(player.heldItem),
            container?.let { ContainerSnapshot(it.javaClass.name.take(256), it.windowId, slots?.size ?: 0,
                selected, match?.slotNumber, (slots?.size ?: 0) <= 512) })
    }

    private fun item(stack: ItemStack?): ItemSnapshot? {
        if (stack == null) return null
        val item = stack.item ?: return null
        val registry = try { Item.itemRegistry.getNameForObject(item)?.take(256) } catch (_: Throwable) { null }
        // Avoid mod-supplied display-name callbacks on the crash path. Registry + metadata are stable identifiers.
        return ItemSnapshot(registry, item.javaClass.name.take(256), stack.itemDamage, stack.stackSize, stack.hasTagCompound())
    }
}
