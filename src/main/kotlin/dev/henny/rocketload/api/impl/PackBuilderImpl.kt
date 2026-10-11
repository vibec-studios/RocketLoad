package dev.henny.rocketload.api.impl

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.henny.rocketload.api.ConflictPolicy
import dev.henny.rocketload.api.EntryFilter
import dev.henny.rocketload.api.PackArchive
import dev.henny.rocketload.api.PackBuilder
import dev.henny.rocketload.fuel.RocketFuelWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.function.BiFunction
import java.util.function.Predicate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackBuilderImpl : PackBuilder {

    fun interface Source {
        fun bytes(): ByteArray
    }

    companion object {
        @JvmStatic
        @Volatile
        var defaultFormat: () -> Int? = { null }

        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    }

    private val sources = LinkedHashMap<String, Source>()
    private var description: String? = null
    private var minFormat: Int? = null
    private var maxFormat: Int? = null
    private var policy = ConflictPolicy.REPLACE
    private var options = RocketFuelWriter.Options()

    override fun description(description: String): PackBuilder = apply { this.description = description }

    override fun packFormat(minFormat: Int, maxFormat: Int): PackBuilder = apply {
        require(minFormat <= maxFormat) { "minFormat > maxFormat" }
        this.minFormat = minFormat
        this.maxFormat = maxFormat
    }

    override fun conflictPolicy(policy: ConflictPolicy): PackBuilder = apply { this.policy = policy }

    private fun putSource(path: String, source: Source) {
        val key = PackPaths.normalize(path)
        if (sources.containsKey(key)) {
            when (policy) {
                ConflictPolicy.KEEP_EXISTING -> return
                ConflictPolicy.FAIL -> throw IllegalStateException("Pfad existiert bereits: $key")
                ConflictPolicy.REPLACE -> sources.remove(key)
            }
        }
        sources[key] = source
    }

    override fun put(path: String, data: ByteArray): PackBuilder = apply {
        val copy = data.copyOf()
        putSource(path, Source { copy })
    }

    override fun put(path: String, file: Path): PackBuilder = apply {
        if (!Files.isRegularFile(file)) throw IllegalArgumentException("Keine Datei: $file")
        putSource(path, Source { Files.readAllBytes(file) })
    }

    override fun putAll(archive: PackArchive, filter: EntryFilter): PackBuilder = apply {
        val iterator = archive.entries(filter).iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            putSource(entry.path(), Source { entry.read() })
        }
    }

    override fun putDirectory(directory: Path, targetPrefix: String): PackBuilder = apply {
        val prefix = targetPrefix.replace('\\', '/').trim('/').let { if (it.isEmpty()) "" else "$it/" }
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                put(prefix + directory.relativize(file).joinToString("/"), file)
            }
        }
    }

    override fun remove(paths: Predicate<String>): PackBuilder = apply {
        sources.keys.removeIf { paths.test(it) }
    }

    override fun relocate(fromPrefix: String, toPrefix: String): PackBuilder = apply {
        val moved = sources.entries.filter { it.key.startsWith(fromPrefix) }
        for ((key, _) in moved) sources.remove(key)
        for ((key, source) in moved) putSource(toPrefix + key.substring(fromPrefix.length), source)
    }

    override fun renameNamespace(from: String, to: String): PackBuilder = apply {
        relocate("assets/$from/", "assets/$to/")
        relocate("data/$from/", "data/$to/")
    }

    override fun transform(paths: Predicate<String>, transformer: BiFunction<String, ByteArray, ByteArray>): PackBuilder = apply {
        for (entry in sources.entries) {
            if (!paths.test(entry.key)) continue
            val original = entry.value
            val key = entry.key
            entry.setValue(Source { transformer.apply(key, original.bytes()) })
        }
    }

    override fun optimize(minifyJson: Boolean, optimizePng: Boolean, deduplicate: Boolean): PackBuilder = apply {
        options = options.copy(jsonMinify = minifyJson, optimizeTextures = optimizePng, deduplicate = deduplicate)
    }

    override fun paths(): Set<String> = LinkedHashSet(sources.keys)

    override fun size(): Int = materializedPaths().size

    private fun materializedPaths(): List<String> {
        val keys = ArrayList(sources.keys)
        if ("pack.mcmeta" !in sources) keys.add(0, "pack.mcmeta")
        return keys
    }

    private fun packMeta(): ByteArray {
        val existing = sources["pack.mcmeta"]
        if (existing != null && description == null && minFormat == null) return existing.bytes()
        val root = existing?.let {
            runCatching { JsonParser.parseString(String(it.bytes(), Charsets.UTF_8)).asJsonObject }.getOrNull()
        } ?: JsonObject()
        val pack = root.getAsJsonObject("pack") ?: JsonObject().also { root.add("pack", it) }
        if (description != null || !pack.has("description")) pack.addProperty("description", description ?: "Erstellt mit RocketLoad")
        val max = maxFormat ?: if (pack.has("max_format") || pack.has("pack_format")) null else defaultFormat()
        val min = minFormat ?: max
        if (min != null && max != null) {
            pack.addProperty("min_format", min)
            pack.addProperty("max_format", max)
            if (min < 65) pack.addProperty("pack_format", max) else pack.remove("pack_format")
            pack.remove("supported_formats")
        }
        return gson.toJson(root).toByteArray(Charsets.UTF_8)
    }

    private inline fun forEachMaterialized(action: (String, ByteArray) -> Unit) {
        action("pack.mcmeta", packMeta())
        for ((path, source) in sources) {
            if (path == "pack.mcmeta") continue
            action(path, source.bytes())
        }
    }

    private fun optimized(path: String, data: ByteArray): ByteArray =
        RocketFuelWriter.optimizeEntry(path, data, options, RocketFuelWriter.Stats())

    override fun writeRocketFuel(target: Path): Path {
        RocketFuelWriter.write(target, 0L, 0L, options, null) { sink ->
            forEachMaterialized { path, data -> sink.accept(path, data) }
        }
        return target
    }

    override fun writeZip(target: Path): Path {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(tmp).buffered(1 shl 16)).use { zip ->
                forEachMaterialized { path, data ->
                    val bytes = optimized(path, data)
                    val entry = ZipEntry(path)
                    if (path.endsWith(".png") || path.endsWith(".ogg")) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = java.util.zip.CRC32().also { it.update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return target
    }

    override fun writeDirectory(target: Path): Path {
        Files.createDirectories(target)
        forEachMaterialized { path, data ->
            val file = PackPaths.resolveInside(target, path)
            file.parent?.let { Files.createDirectories(it) }
            Files.write(file, optimized(path, data))
        }
        return target
    }

    override fun build(): PackArchive {
        val data = LinkedHashMap<String, ByteArray>()
        forEachMaterialized { path, bytes -> data[path] = bytes }
        return MemoryArchive(data)
    }
}
