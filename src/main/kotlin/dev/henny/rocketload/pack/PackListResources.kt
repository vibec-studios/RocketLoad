package dev.henny.rocketload.pack

import net.minecraft.resource.AbstractFileResourcePack
import net.minecraft.resource.InputSupplier
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourcePackInfo
import net.minecraft.resource.ResourceType
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.InputStream

class PackListResources(
    info: ResourcePackInfo,
    private val mcmeta: ByteArray,
    private val png: ByteArray?,
) : AbstractFileResourcePack(info) {

    override fun openRoot(vararg segments: String): InputSupplier<InputStream>? {
        return when (segments.joinToString("/")) {
            "pack.mcmeta" -> InputSupplier { ByteArrayInputStream(mcmeta) }
            "pack.png" -> png?.let { bytes -> InputSupplier { ByteArrayInputStream(bytes) } }
            else -> null
        }
    }

    override fun open(type: ResourceType, id: Identifier): InputSupplier<InputStream>? = null

    override fun findResources(
        type: ResourceType,
        namespace: String,
        prefix: String,
        consumer: ResourcePack.ResultConsumer,
    ) {
    }

    override fun getNamespaces(type: ResourceType): Set<String> = emptySet()

    override fun close() {
    }
}
