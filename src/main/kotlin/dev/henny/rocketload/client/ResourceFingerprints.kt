package dev.henny.rocketload.client

import dev.henny.rocketload.fuel.RocketFuelCache
import dev.henny.rocketload.fuel.RocketFuelFile
import dev.henny.rocketload.fuel.RocketFuelWriter
import dev.henny.rocketload.mixin.CompositePackAccessor
import dev.henny.rocketload.mixin.PathPackResourcesAccessor
import dev.henny.rocketload.pack.RocketFuelPackResources
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.VanillaPackResources
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

object ResourceFingerprints {

    private val fuelByPack = Collections.synchronizedMap(WeakHashMap<Any, RocketFuelFile>())
    private val zipByPack = Collections.synchronizedMap(WeakHashMap<Any, File>())
    private val downloadByPack = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val volatileCounter = AtomicLong()

    fun registerZip(pack: Any, file: File) {
        zipByPack[pack] = file
    }

    fun registerDownload(pack: Any, source: Path) {
        RocketFuelCache.verifiedDownloadId(source)?.let { downloadByPack[pack] = it }
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

    internal class PackFingerprint(
        val token: String,
        val domains: RocketFuelFile.Domains?,
        val soundToken: String = token,
    )

    internal fun snapshot(packs: List<Any>): List<PackFingerprint> = packs.map { pack ->
        val fuel = fuelOf(pack)
        val zip = zipByPack[pack]
        when {
            fuel != null -> {
                val id = downloadByPack[pack]
                val token = if (id == null) "fuel:${fuel.path.fileName}:${fuel.sourceSize}:${fuel.sourceMtime}" else "download:$id"
                PackFingerprint(token, fuel.domains)
            }
            zip != null -> zipFingerprint(pack, zip)
            else -> PackFingerprint(token(pack), null)
        }
    }

    private fun zipFingerprint(pack: Any, file: File): PackFingerprint {
        val packId = packId(pack)
        // Vanilla sound suppliers still point at the old ZIP after the manager closes it.
        val soundToken = volatileToken(packId)
        val id = RocketFuelCache.verifiedDownloadId(file.toPath())
            ?: return PackFingerprint(fileToken(file, packId), null, soundToken)
        return try {
            val domains = RocketFuelFile.Companion.DomainCollector()
            ZipFile(file).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (!entry.isDirectory && !RocketFuelWriter.isJunk(entry.name)) domains.note(entry.name)
                }
                zip.getEntry("pack.mcmeta")?.let { entry ->
                    zip.getInputStream(entry).use { domains.noteMetadata(it.readNBytes(RocketFuelFile.MAX_META_SCAN + 1)) }
                }
            }
            PackFingerprint("download:$id", domains.build(), soundToken)
        } catch (_: IOException) {
            PackFingerprint(fileToken(file, packId), null, soundToken)
        }
    }

    internal fun fingerprint(
        packs: List<PackFingerprint>,
        sounds: Boolean = false,
        relevant: (RocketFuelFile.Domains) -> Boolean,
    ): List<String> = packs.mapNotNull { pack ->
        val domains = pack.domains
        // Overlays and filters can affect domains without paths in the primary pack.
        val token = if (sounds) pack.soundToken else pack.token
        token.takeIf { domains == null || domains.opaque || relevant(domains) }
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

    private fun fileToken(file: File, id: String): String {
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
