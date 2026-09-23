package dev.henny.rocketload.fuel

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.NavigableMap
import java.util.TreeMap

class RocketFuelFile private constructor(
    val path: Path,
    private val channel: FileChannel,
    val entries: NavigableMap<String, Entry>,
    val sourceSize: Long,
    val sourceMtime: Long,
    val writerVersion: Int,
    val domains: Domains,
) : AutoCloseable {

    class Domains(
        val sounds: Boolean,
        val texts: Boolean,
        val colormaps: Boolean,
        val lang: Boolean,
        val fonts: Boolean,
        val shaders: Boolean,
        val textures: Boolean,
        val models: Boolean,
        val atlases: Boolean,
        val particles: Boolean,
        val visuals: Boolean,
        val geometry: Boolean,
    )

    class Entry(val offset: Long, val size: Int)

    class Header(
        val sourceSize: Long,
        val sourceMtime: Long,
        val indexOffset: Long,
        val entryCount: Int,
        val writerVersion: Int,
    )

    fun contains(path: String): Boolean = entries.containsKey(path)

    fun entry(path: String): Entry? = entries[path]

    fun openStream(entry: Entry): InputStream {
        val data = ByteArray(entry.size)
        if (entry.size == 0) return java.io.ByteArrayInputStream(data)
        val buf = ByteBuffer.wrap(data)
        var pos = entry.offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) {
                throw IOException("Unvollstaendiger Fuel-Eintrag ${path.fileName} @ $pos (rest=${buf.remaining()})")
            }
            pos += n
        }
        return java.io.ByteArrayInputStream(data)
    }

    inline fun forEachWithPrefix(prefix: String, action: (String, Entry) -> Unit) {
        for ((path, entry) in entries.tailMap(prefix, true)) {
            if (!path.startsWith(prefix)) break
            action(path, entry)
        }
    }

    fun namespaces(root: String): Set<String> {
        val result = LinkedHashSet<String>()
        val prefix = "$root/"
        var key: String? = entries.ceilingKey(prefix)
        while (key != null && key.startsWith(prefix)) {
            val slash = key.indexOf('/', prefix.length)
            if (slash < 0) {
                key = entries.higherKey(key)
                continue
            }
            val namespace = key.substring(prefix.length, slash)
            result += namespace
            key = entries.ceilingKey("$prefix$namespace/￿")
        }
        return result
    }

    override fun close() {
        channel.close()
    }

    companion object {
        @Throws(IOException::class)
        fun readHeader(path: Path): Header {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val buf = ByteBuffer.allocate(RocketFuelFormat.HEADER_SIZE)
                while (buf.hasRemaining()) {
                    if (channel.read(buf) < 0) throw IOException("Datei zu klein: $path")
                }
                buf.flip()
                return parseHeader(buf, path)
            }
        }

        private fun parseHeader(buf: ByteBuffer, path: Path): Header {
            val magic = ByteArray(RocketFuelFormat.MAGIC.size)
            buf.get(magic)
            if (!magic.contentEquals(RocketFuelFormat.MAGIC)) throw IOException("Kein .rocketfuel: $path")
            val version = buf.int
            if (version != RocketFuelFormat.VERSION) throw IOException("Unbekannte Formatversion $version: $path")
            val sourceSize = buf.long
            val sourceMtime = buf.long
            val indexOffset = buf.long
            val entryCount = buf.int
            val writerVersion = buf.int
            return Header(sourceSize, sourceMtime, indexOffset, entryCount, writerVersion)
        }

        private class DomainCollector {
            var sounds = false
            var texts = false
            var colormaps = false
            var lang = false
            var fonts = false
            var shaders = false
            var textures = false
            var models = false
            var atlases = false
            var particles = false
            var visuals = false
            var geometry = false

            fun note(path: String) {
                if (!path.startsWith("assets/")) return
                val nsEnd = path.indexOf('/', "assets/".length)
                if (nsEnd < 0) return
                val dirEnd = path.indexOf('/', nsEnd + 1)
                val dir = if (dirEnd < 0) path.substring(nsEnd + 1) else path.substring(nsEnd + 1, dirEnd)
                val rest = if (dirEnd < 0) "" else path.substring(dirEnd + 1)
                when (dir) {
                    "sounds", "sounds.json" -> sounds = true
                    "texts" -> texts = true
                    "lang" -> lang = true
                    "font" -> {
                        fonts = true
                    }
                    "shaders", "post_effect" -> {
                        shaders = true
                        geometry = true
                    }
                    "textures" -> {
                        textures = true
                        visuals = true
                        if (rest.startsWith("font/")) fonts = true
                        if (rest.startsWith("colormap/")) {
                            colormaps = true
                            geometry = true
                        }
                    }
                    "models", "blockstates" -> {
                        models = true
                        visuals = true
                        geometry = true
                    }
                    "items" -> {
                        models = true
                        visuals = true
                    }
                    "atlases" -> {
                        atlases = true
                        visuals = true
                        geometry = true
                    }
                    "particles" -> {
                        particles = true
                        visuals = true
                    }
                }
            }

            fun build() = Domains(
                sounds, texts, colormaps, lang, fonts, shaders,
                textures, models, atlases, particles, visuals, geometry,
            )
        }

        @Throws(IOException::class)
        fun open(path: Path): RocketFuelFile {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                if (size < RocketFuelFormat.HEADER_SIZE) throw IOException("Datei zu klein: $path")
                if (size > Int.MAX_VALUE) throw IOException("Packs > 2 GiB werden nicht unterstuetzt: $path")

                val headerBuf = ByteBuffer.allocate(RocketFuelFormat.HEADER_SIZE).order(java.nio.ByteOrder.BIG_ENDIAN)
                var headerPos = 0L
                while (headerBuf.hasRemaining()) {
                    val n = channel.read(headerBuf, headerPos)
                    if (n <= 0) throw IOException("Datei zu klein: $path")
                    headerPos += n
                }
                headerBuf.flip()
                val header = parseHeader(headerBuf, path)
                if (header.indexOffset < RocketFuelFormat.HEADER_SIZE || header.indexOffset > size) {
                    throw IOException("Korrupter Index-Offset: $path")
                }

                val indexSize = (size - header.indexOffset).toInt()
                val index = ByteBuffer.allocate(indexSize).order(java.nio.ByteOrder.BIG_ENDIAN)
                var indexPos = header.indexOffset
                while (index.hasRemaining()) {
                    val n = channel.read(index, indexPos)
                    if (n <= 0) throw IOException("Korrupter Index: $path")
                    indexPos += n
                }
                index.flip()

                val entries = TreeMap<String, Entry>()
                val domains = DomainCollector()
                repeat(header.entryCount) {
                    val pathLen = index.int
                    if (pathLen < 0 || pathLen > index.remaining()) throw IOException("Korrupter Index: $path")
                    val pathBytes = ByteArray(pathLen)
                    index.get(pathBytes)
                    val entryPath = String(pathBytes, Charsets.UTF_8)
                    val offset = index.long
                    val entrySize = index.int
                    if (offset < 0 || entrySize < 0 || offset + entrySize > header.indexOffset) {
                        throw IOException("Korrupter Eintrag '$entryPath': $path")
                    }
                    entries[entryPath] = Entry(offset, entrySize)
                    domains.note(entryPath)
                }

                return RocketFuelFile(
                    path, channel, entries,
                    header.sourceSize, header.sourceMtime, header.writerVersion,
                    domains.build(),
                )
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }
    }
}
