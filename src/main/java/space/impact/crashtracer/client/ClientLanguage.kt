package space.impact.crashtracer.client

import net.minecraft.client.Minecraft

object ClientLanguage {
    fun current(): String = Minecraft.getMinecraft().gameSettings?.language ?: "en_US"
}
