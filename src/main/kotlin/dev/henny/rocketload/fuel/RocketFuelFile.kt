package dev.henny.rocketload.fuel

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.NavigableMap
import java.util.TreeMap

class RocketFuelFile private constructor(
    val path: Path,
    channel: FileChannel,
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
        val blockModels: Boolean,
        val atlases: Boolean,
        val particles: Boolean,
        val unknown: Boolean,
        /** Overlays or a pack.mcmeta filter: path-based domain detection can't see their effect. */
        val opaque: Boolean = false,
    ) {
        val visuals: Boolean get() = textures || models || atlases || particles || unknown
        val geometry: Boolean get() = blockModels || colormaps || unknown
    }

    class Entry(val offset: Long, val size: Int)

    class Header(
        val sourceSize: Long,
        val sourceMtime: Long,
        val indexOffset: Long,
        val entryCount: Int,
        val writerVersion: Int,
    )

    @Volatile
    private var channel: FileChannel = channel

    @Volatile
    private var closed = false

    fun contains(path: String): Boolean = entries.containsKey(path)

    fun entry(path: String): Entry? = entries[path]

    fun openStream(entry: Entry): InputStream = FuelEntryStream(this, entry)

    fun readBytes(entry: Entry): ByteArray {
        val data = ByteArray(entry.size)
        if (entry.size > 0) read(entry, ByteBuffer.wrap(data))
        return data
    }

    fun read(entry: Entry, target: ByteBuffer) {
        if (target.remaining() < entry.size) throw IOException("Puffer zu klein fuer ${path.fileName}")
        val view = target.duplicate()
        view.limit(view.position() + entry.size)
        var pos = entry.offset
        var reopened = 0
        while (view.hasRemaining()) {
            val interrupted = Thread.interrupted()
            try {
                val n = channel.read(view, pos)
                if (n < 0) throw IOException("Unvollstaendiger Fuel-Eintrag ${path.fileName} @ $pos")
                pos += n
            } catch (e: ClosedChannelException) {
                if (closed || ++reopened > 3) throw e
                reopen()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        target.position(target.position() + entry.size)
    }

    @Synchronized
    private fun reopen() {
        if (closed || channel.isOpen) return
        channel = FileChannel.open(path, StandardOpenOption.READ)
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

    @Synchronized
    override fun close() {
        closed = true
        channel.close()
    }

    companion object {
        private val KNOWN_ASSET_DIRS = setOf(
            "sounds", "sounds.json", "texts", "lang", "font", "shaders", "post_effect", "textures",
            "models", "blockstates", "items", "atlases", "particles", "equipment", "waypoint_style",
            "regional_compliancies.json", "gpu_warnlist.json", "icons",
        )

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
            return Header(buf.long, buf.long, buf.long, buf.int, buf.int)
        }

        internal class DomainCollector {
            var sounds = false
            var texts = false
            var colormaps = false
            var lang = false
            var fonts = false
            var shaders = false
            var textures = false
            var models = false
            var blockModels = false
            var atlases = false
            var particles = false
            var unknown = false
            var opaque = false

            fun note(path: String) {
                if (!path.startsWith("assets/")) {
                    if (path.indexOf('/') > 0 && !path.startsWith("data/")) opaque = true
                    return
                }
                val nsEnd = path.indexOf('/', "assets/".length)
                if (nsEnd < 0) return
                val dirEnd = path.indexOf('/', nsEnd + 1)
                val dir = if (dirEnd < 0) path.substring(nsEnd + 1) else path.substring(nsEnd + 1, dirEnd)
                val rest = if (dirEnd < 0) "" else path.substring(dirEnd + 1)
                when (dir) {
                    "sounds", "sounds.json" -> sounds = true
                    "texts" -> texts = true
                    "lang" -> lang = true
                    "font" -> fonts = true
                    "shaders", "post_effect" -> shaders = true
                    "textures" -> {
                        textures = true
                        if (rest.startsWith("font/")) fonts = true
                        if (rest.startsWith("colormap/")) colormaps = true
                    }
                    "models", "blockstates" -> {
                        models = true
                        blockModels = true
                    }
                    "items" -> models = true
                    "atlases" -> atlases = true
                    "particles" -> particles = true
                    else -> if (dirEnd >= 0 && dir !in KNOWN_ASSET_DIRS) unknown = true
                }
            }

            fun build() = Domains(
                sounds, texts, colormaps, lang, fonts, shaders,
                textures, models, blockModels, atlases, particles, unknown, opaque,
            )
        }

        @Throws(IOException::class)
        fun open(path: Path): RocketFuelFile {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                if (size < RocketFuelFormat.HEADER_SIZE) throw IOException("Datei zu klein: $path")
                if (size > Int.MAX_VALUE) throw IOException("Packs > 2 GiB werden nicht unterstuetzt: $path")

                val headerBuf = ByteBuffer.allocate(RocketFuelFormat.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
                readFully(channel, headerBuf, 0L, path)
                headerBuf.flip()
                val header = parseHeader(headerBuf, path)
                if (header.indexOffset < RocketFuelFormat.HEADER_SIZE || header.indexOffset > size) {
                    throw IOException("Korrupter Index-Offset: $path")
                }

                val index = ByteBuffer.allocate((size - header.indexOffset).toInt()).order(ByteOrder.BIG_ENDIAN)
                readFully(channel, index, header.indexOffset, path)
                index.flip()

                val entries = TreeMap<String, Entry>()
                val domains = DomainCollector()
                repeat(header.entryCount) {
                    if (index.remaining() < 4) throw IOException("Korrupter Index: $path")
                    val pathLen = index.int
                    if (pathLen < 0 || pathLen + 12 > index.remaining()) throw IOException("Korrupter Index: $path")
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
                entries["pack.mcmeta"]?.let { meta ->
                    if (meta.size in 1..MAX_META_SCAN) {
                        val metaBuf = ByteBuffer.allocate(meta.size)
                        readFully(channel, metaBuf, meta.offset, path)
                        if (String(metaBuf.array(), Charsets.UTF_8).contains("\"filter\"")) domains.opaque = true
                    }
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

        private const val MAX_META_SCAN = 256 * 1024

        private fun readFully(channel: FileChannel, buf: ByteBuffer, start: Long, path: Path) {
            var pos = start
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n <= 0) throw IOException("Datei zu klein: $path")
                pos += n
            }
        }
    }
}
