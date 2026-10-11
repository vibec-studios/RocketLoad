package dev.henny.rocketload.pack

import dev.henny.rocketload.fuel.RocketFuelCache
import dev.henny.rocketload.fuel.RocketFuelFile
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.IoSupplier
import org.slf4j.LoggerFactory
import java.io.InputStream
//? if >=26.3 {
import net.minecraft.server.packs.AbstractPackMetadataResources
//?} else {
/*import net.minecraft.server.packs.AbstractPackResources
*///?}

class RocketFuelPackResources(
    info: PackLocationInfo,
    val fuel: RocketFuelFile,
    private val prefix: String = "",
    private val releaseOnClose: Boolean = true,
//? if >=26.3 {
) : AbstractPackMetadataResources(info), PackResources {
//?} else {
/*) : AbstractPackResources(info) {
*///?}

    private fun addPrefix(path: String): String = if (prefix.isEmpty()) path else "$prefix/$path"

    override fun getRootResource(vararg path: String): IoSupplier<InputStream>? =
        resource(path.joinToString("/"))

    override fun getResource(type: PackType, id: Identifier): IoSupplier<InputStream>? =
        resource("${type.directory}/${id.namespace}/${id.path}")

    private fun resource(path: String): IoSupplier<InputStream>? {
        val entry = fuel.entry(addPrefix(path)) ?: return null
        return IoSupplier { fuel.openStream(entry) }
    }

    override fun getNamespaces(type: PackType): Set<String> {
        val result = LinkedHashSet<String>()
        for (namespace in fuel.namespaces(addPrefix(type.directory))) {
            if (Identifier.isValidNamespace(namespace)) {
                result += namespace
            } else {
                LOGGER.warn("Ungueltiger Namespace {} in {}, wird ignoriert", namespace, fuel.path.fileName)
            }
        }
        return result
    }

    override fun listResources(type: PackType, namespace: String, directory: String, output: PackResources.ResourceOutput) {
        val root = addPrefix("${type.directory}/$namespace/")
        fuel.forEachWithPrefix("$root$directory/") { path, entry ->
            val relative = path.substring(root.length)
            val id = Identifier.tryBuild(namespace, relative)
            if (id != null) {
                output.accept(id, IoSupplier { fuel.openStream(entry) })
            } else {
                LOGGER.warn("Ungueltiger Pfad im Pack: {}:{}, wird ignoriert", namespace, relative)
            }
        }
    }

    override fun close() {
        if (releaseOnClose) RocketFuelCache.release(fuel)
    }

    private companion object {
        private val LOGGER = LoggerFactory.getLogger("RocketLoad")
    }
}
