package dev.henny.rocketload.pack

import dev.henny.rocketload.fuel.RocketFuelCache
import dev.henny.rocketload.fuel.RocketFuelFile
import net.minecraft.resource.AbstractFileResourcePack
import net.minecraft.resource.InputSupplier
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourcePackInfo
import net.minecraft.resource.ResourceType
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.io.InputStream

class RocketFuelPackResources(
    info: ResourcePackInfo,
    val fuel: RocketFuelFile,
    private val prefix: String = "",
    private val releaseOnClose: Boolean = true,
) : AbstractFileResourcePack(info) {

    private fun addPrefix(path: String): String = if (prefix.isEmpty()) path else "$prefix/$path"

    override fun openRoot(vararg path: String): InputSupplier<InputStream>? =
        resource(path.joinToString("/"))

    override fun open(type: ResourceType, id: Identifier): InputSupplier<InputStream>? =
        resource("${type.directory}/${id.namespace}/${id.path}")

    private fun resource(path: String): InputSupplier<InputStream>? {
        val entry = fuel.entry(addPrefix(path)) ?: return null
        return InputSupplier { fuel.openStream(entry) }
    }

    override fun getNamespaces(type: ResourceType): Set<String> {
        val result = LinkedHashSet<String>()
        for (namespace in fuel.namespaces(addPrefix(type.directory))) {
            if (Identifier.isNamespaceValid(namespace)) {
                result += namespace
            } else {
                LOGGER.warn("Ungueltiger Namespace {} in {}, wird ignoriert", namespace, fuel.path.fileName)
            }
        }
        return result
    }

    override fun findResources(
        type: ResourceType,
        namespace: String,
        prefix: String,
        consumer: ResourcePack.ResultConsumer,
    ) {
        val root = addPrefix("${type.directory}/$namespace/")
        fuel.forEachWithPrefix("$root$prefix/") { path, entry ->
            val relative = path.substring(root.length)
            val id = Identifier.tryParse(namespace, relative)
            if (id != null) {
                consumer.accept(id, InputSupplier { fuel.openStream(entry) })
            } else {
                LOGGER.warn("Ungueltiger Pfad im Pack: {}:{}, wird ignoriert", namespace, relative)
            }
        }
    }

    override fun close() {
        if (releaseOnClose) {
            RocketFuelCache.release(fuel)
        }
    }

    private companion object {
        private val LOGGER = LoggerFactory.getLogger("RocketLoad")
    }
}
