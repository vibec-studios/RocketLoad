package dev.henny.rocketload.pack

import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.resources.IoSupplier
import java.io.ByteArrayInputStream
import java.io.InputStream
//? if >=26.3 {
import net.minecraft.server.packs.AbstractPackMetadataResources
//?} else {
/*import net.minecraft.resources.Identifier
import net.minecraft.server.packs.AbstractPackResources
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
*///?}

class PackListResources(
    info: PackLocationInfo,
    private val mcmeta: ByteArray,
    private val png: ByteArray?,
//? if >=26.3 {
) : AbstractPackMetadataResources(info) {
//?} else {
/*) : AbstractPackResources(info) {
*///?}

    override fun getRootResource(vararg path: String): IoSupplier<InputStream>? {
        return when (path.joinToString("/")) {
            "pack.mcmeta" -> IoSupplier { ByteArrayInputStream(mcmeta) }
            "pack.png" -> png?.let { bytes -> IoSupplier { ByteArrayInputStream(bytes) } }
            else -> null
        }
    }

    //? if <26.3 {
    /*override fun getResource(type: PackType, id: Identifier): IoSupplier<InputStream>? = null

    override fun listResources(type: PackType, namespace: String, prefix: String, output: PackResources.ResourceOutput) {
    }

    override fun getNamespaces(type: PackType): Set<String> = emptySet()
    *///?}

    override fun close() {
    }
}
