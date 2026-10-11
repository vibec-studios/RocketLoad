package dev.henny.rocketload.client

import dev.henny.rocketload.fuel.RocketFuelFile
import dev.henny.rocketload.mixin.CompositePackAccessor
import dev.henny.rocketload.mixin.PathPackResourcesAccessor
import dev.henny.rocketload.pack.RocketFuelPackResources
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.VanillaPackResources
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

object ResourceFingerprints {

    private val fuelByPack = Collections.synchronizedMap(WeakHashMap<Any, RocketFuelFile>())
    private val zipByPack = Collections.synchronizedMap(WeakHashMap<Any, java.io.File>())
    private val volatileCounter = AtomicLong()

    fun registerZip(pack: Any, file: java.io.File) {
        zipByPack[pack] = file
    }

    fun register(pack: Any, fuel: RocketFuelFile) {
        fuelByPack[pack] = fuel
    }

    fun fuelOf(pack: Any): RocketFuelFile? = (pack as? RocketFuelPackResources)?.fuel ?: fuelByPack[pack]

    fun describe(pack: Any): String {
        val fuel = fuelOf(pack) ?: return pack.javaClass.simpleName
        val d = fuel.domains
        return "fuel ${fuel.path.fileName} entries=${fuel.entries.size} tex=${d.textures} models=${d.models} atlases=${d.atlases} geo=${d.geometry} unknown=${d.unknown}"
    }

    fun fingerprint(
        packs: List<Any>,
        relevant: (RocketFuelFile.Domains) -> Boolean,
        includeOpaque: Boolean = false,
    ): List<String> =
        packs.mapNotNull { pack ->
            val fuel = fuelOf(pack)
            if (fuel != null) {
                val counts = relevant(fuel.domains) || (includeOpaque && fuel.domains.opaque)
                if (counts) "fuel:${fuel.path.fileName}:${fuel.sourceSize}:${fuel.sourceMtime}" else null
            } else {
                token(pack)
            }
        }

    private fun token(pack: Any): String {
        val id = packId(pack)
        return when {
            pack is VanillaPackResources -> "vanilla:$id"
            zipByPack[pack] != null -> fileToken(zipByPack[pack]!!, id)
            pack is PathPackResources -> pathToken(pack, id)
            pack is CompositePackAccessor -> pack.rocketload_primary()?.let { token(it) } ?: volatileToken(id)
            pack.javaClass.name.startsWith("net.fabricmc.") -> "mod:$id"
            else -> "other:${pack.javaClass.name}:$id"
        }
    }

    private fun packId(pack: Any): String =
        (pack as? net.minecraft.server.packs.PackResources)?.packId() ?: pack.javaClass.simpleName

    private fun volatileToken(id: String): String = "volatile:$id:${volatileCounter.incrementAndGet()}"

    private fun fileToken(file: java.io.File, id: String): String {
        return try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            "zip:$id:${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
        } catch (_: Exception) {
            volatileToken(id)
        }
    }

    private fun pathToken(pack: PathPackResources, id: String): String {
        val root = runCatching { (pack as PathPackResourcesAccessor).rocketload_root() }.getOrNull()
        return if (root != null && root.fileSystem != FileSystems.getDefault()) "builtin:$id" else volatileToken(id)
    }
}
