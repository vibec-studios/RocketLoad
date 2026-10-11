package dev.henny.rocketload.api.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.henny.rocketload.api.EntryFilter
import dev.henny.rocketload.api.PackArchive
import dev.henny.rocketload.api.PackBuilder
import dev.henny.rocketload.api.PackEntry
import dev.henny.rocketload.api.PackMeta
import dev.henny.rocketload.fuel.RocketFuelFile
import dev.henny.rocketload.fuel.RocketFuelFormat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.OptionalInt
import java.util.TreeMap
import java.util.stream.Stream
import java.util.zip.ZipFile

internal object PackPaths {
    fun normalize(path: String): String {
        val cleaned = path.replace('\\', '/').trimStart('/')
        require(cleaned.isNotEmpty()) { "Leerer Pfad" }
        require(cleaned.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Ungueltiger Pfad: $path" }
        return cleaned
    }

    fun resolveInside(root: Path, path: String): Path {
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(normalize(path)).normalize()
        if (!target.startsWith(base)) throw IOException("Pfad verlaesst das Zielverzeichnis: $path")
        return target
    }
}

internal abstract class BaseArchive(private val sourcePath: Path?, private val format: PackArchive.Format) : PackArchive {
    protected abstract val index: TreeMap<String, PackEntry>

    override fun source(): Optional<Path> = Optional.ofNullable(sourcePath)

    override fun format(): PackArchive.Format = format

    override fun size(): Int = index.size

    override fun contains(path: String): Boolean = index.containsKey(path)

    override fun entry(path: String): Optional<PackEntry> = Optional.ofNullable(index[path])

    override fun entries(): Stream<PackEntry> = index.values.stream()

    override fun namespaces(root: String): Set<String> {
        val prefix = "${root.trimEnd('/')}/"
        val result = LinkedHashSet<String>()
        for (key in index.tailMap(prefix, true).keys) {
            if (!key.startsWith(prefix)) break
            val slash = key.indexOf('/', prefix.length)
            if (slash > prefix.length) result += key.substring(prefix.length, slash)
        }
        return result
    }

    override fun meta(): Optional<PackMeta> {
        val entry = index["pack.mcmeta"] ?: return Optional.empty()
        return try {
            val raw = String(entry.read(), Charsets.UTF_8)
            val root = JsonParser.parseString(raw).asJsonObject
            val pack = root.getAsJsonObject("pack") ?: JsonObject()
            val description = pack.get("description")?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: ""
            val min = formatOf(pack, "min_format") ?: formatOf(pack, "pack_format")
            val max = formatOf(pack, "max_format") ?: formatOf(pack, "pack_format")
            val overlays = root.getAsJsonObject("overlays")?.getAsJsonArray("entries")
                ?.mapNotNull { it.asJsonObject.get("directory")?.asString }
                .orEmpty()
            Optional.of(
                PackMeta(
                    description,
                    min?.let { OptionalInt.of(it) } ?: OptionalInt.empty(),
                    max?.let { OptionalInt.of(it) } ?: OptionalInt.empty(),
                    overlays,
                    raw,
                ),
            )
        } catch (_: Exception) {
            Optional.empty()
        }
    }

    private fun formatOf(pack: JsonObject, key: String): Int? {
        val element = pack.get(key) ?: return null
        return when {
            element.isJsonPrimitive -> element.asInt
            element.isJsonArray && element.asJsonArray.size() > 0 -> element.asJsonArray[0].asInt
            else -> null
        }
    }

    override fun extract(filter: EntryFilter, targetDirectory: Path): Int {
        var count = 0
        val iterator = entries(filter).iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = PackPaths.resolveInside(targetDirectory, entry.path())
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, entry.read())
            count++
        }
        return count
    }

    override fun toBuilder(filter: EntryFilter): PackBuilder = PackBuilderImpl().putAll(this, filter)
}

internal class SimpleEntry(
    private val path: String,
    private val size: Long,
    private val reader: () -> ByteArray,
) : PackEntry {
    override fun path(): String = path

    override fun size(): Long = size

    override fun read(): ByteArray = reader()

    override fun open(): InputStream = ByteArrayInputStream(reader())

    override fun toString(): String = "PackEntry($path, $size B)"
}

internal class FuelArchive(path: Path) : BaseArchive(path, PackArchive.Format.ROCKETFUEL) {
    private val fuel = RocketFuelFile.open(path)

    override val index: TreeMap<String, PackEntry> = TreeMap<String, PackEntry>().also { map ->
        for ((entryPath, entry) in fuel.entries) {
            map[entryPath] = object : PackEntry {
                override fun path(): String = entryPath

                override fun size(): Long = entry.size.toLong()

                override fun read(): ByteArray = fuel.readBytes(entry)

                override fun open(): InputStream = fuel.openStream(entry)
            }
        }
    }

    val sourceSize: Long get() = fuel.sourceSize

    val sourceMtime: Long get() = fuel.sourceMtime

    override fun close() {
        fuel.close()
    }
}

internal class ZipArchive(path: Path) : BaseArchive(path, PackArchive.Format.ZIP) {
    private val zip = ZipFile(path.toFile())

    override val index: TreeMap<String, PackEntry> = TreeMap<String, PackEntry>().also { map ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/')
            map[name] = object : PackEntry {
                override fun path(): String = name

                override fun size(): Long = entry.size

                override fun read(): ByteArray = synchronized(zip) { zip.getInputStream(entry).use { it.readBytes() } }

                override fun open(): InputStream = ByteArrayInputStream(read())
            }
        }
    }

    override fun close() {
        zip.close()
    }
}

internal class DirectoryArchive(private val root: Path) : BaseArchive(root, PackArchive.Format.DIRECTORY) {
    override val index: TreeMap<String, PackEntry> = TreeMap<String, PackEntry>().also { map ->
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val rel = root.relativize(file).joinToString("/")
                map[rel] = object : PackEntry {
                    override fun path(): String = rel

                    override fun size(): Long = Files.size(file)

                    override fun read(): ByteArray = Files.readAllBytes(file)

                    override fun open(): InputStream = Files.newInputStream(file)
                }
            }
        }
    }

    override fun close() {
    }
}

internal class MemoryArchive(data: Map<String, ByteArray>) : BaseArchive(null, PackArchive.Format.MEMORY) {
    override val index: TreeMap<String, PackEntry> = TreeMap<String, PackEntry>().also { map ->
        for ((path, bytes) in data) map[path] = SimpleEntry(path, bytes.size.toLong()) { bytes.copyOf() }
    }

    override fun close() {
    }
}

internal object ArchiveOpener {
    fun open(path: Path): PackArchive {
        if (Files.isDirectory(path)) return DirectoryArchive(path)
        if (!Files.isRegularFile(path)) throw IOException("Pack nicht gefunden: $path")
        if (isRocketFuel(path)) return FuelArchive(path)
        return ZipArchive(path)
    }

    private fun isRocketFuel(path: Path): Boolean {
        if (path.fileName.toString().endsWith(RocketFuelFormat.FILE_EXTENSION)) return true
        return try {
            Files.newInputStream(path).use { input ->
                val magic = ByteArray(RocketFuelFormat.MAGIC.size)
                input.readNBytes(magic, 0, magic.size) == magic.size && magic.contentEquals(RocketFuelFormat.MAGIC)
            }
        } catch (_: IOException) {
            false
        }
    }
}
