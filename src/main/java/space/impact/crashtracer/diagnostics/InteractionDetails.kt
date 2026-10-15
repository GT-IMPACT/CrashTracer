package space.impact.crashtracer.diagnostics

/** Pure formatting/classification also used by the standalone analyzer and old reports. */
object InteractionDetails {
    fun isRightClick(report: IncidentReport) = report.interaction?.action in setOf("RIGHT_CLICK_AIR", "RIGHT_CLICK_BLOCK") ||
        report.exceptions.nodes.any { node -> node.frames.any {
            it.contains("NetHandlerPlayServer.processPlayerBlockPlacement(") ||
                it.contains("NetHandlerPlayServer.func_147346_a(") || it.contains("C08PacketPlayerBlockPlacement.")
        } }

    fun isPacketFailure(report: IncidentReport) = report.interaction != null || isRightClick(report) ||
        (report.description in setOf("Ticking memory connection", "Ticking connection") &&
            report.exceptions.nodes.any { node -> node.frames.any { it.contains("net.minecraft.network.") } })

    fun text(snapshot: InteractionSnapshot): String = buildString {
        append("Packet: ${snapshot.packetClass}\nAction: ${snapshot.action ?: "unknown"}\n")
        append("Captured at (epoch ms): ${snapshot.capturedAt}\n")
        snapshot.packetFields.forEach { (key, value) -> append("$key: $value\n") }
        append("Packet item: ${item(snapshot.packetItem)}\n")
        for ((label, state) in listOf("Before processing" to snapshot.before, "At failure" to snapshot.atFailure)) {
            append("\n$label:\n")
            if (state == null) { append("unavailable\n"); continue }
            append("Dimension: ${state.dimension}; sneaking: ${state.sneaking}\nHeld item: ${item(state.heldItem)}\n")
            val container = state.container
            if (container == null) append("Container: unavailable\n") else {
                append("Container: ${container.type}; window=${container.windowId}; slots=${container.slotCount}\n")
                append("Selected inventory index: ${container.selectedInventorySlot}; matching container slot: ${container.matchingSlotNumber ?: "not found"}; scan complete=${container.slotScanComplete}\n")
            }
        }
        append("\nThis is interaction context, not proof that the item or container caused the crash.\n")
        snapshot.limitations.forEach { append("- $it\n") }
    }

    private fun item(item: ItemSnapshot?) = item?.let {
        "${it.registryName ?: it.itemClass} metadata=${it.metadata} count=${it.count} nbtPresent=${it.hasNbt} class=${it.itemClass}"
    } ?: "empty / unavailable"
}
