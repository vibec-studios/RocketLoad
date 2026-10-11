package dev.henny.rocketload.fuel

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipFile

object RocketFuelWriter {

    data class Options(
        val jsonMinify: Boolean = true,
        val deduplicate: Boolean = true,
        val optimizeTextures: Boolean = true,
        val pngCompressionLevel: Int = 6,
    )

    class Stats {
        val entries = AtomicInteger()
        val junkSkipped = AtomicInteger()
        val jsonBytesSaved = AtomicLong()
        val pngBytesSaved = AtomicLong()
        val dedupedBytes = AtomicLong()
    }

    private const val MAX_IN_FLIGHT_BYTES = 96L * 1024 * 1024
    private const val MAX_IN_FLIGHT_ENTRIES = 512

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val JUNK_FILE_NAMES = setOf(".DS_Store", "Thumbs.db", "desktop.ini", ".gitignore", ".gitattributes")

    fun isJunk(path: String): Boolean =
        path.startsWith("__MACOSX/") ||
            path.startsWith(".git/") || path.contains("/.git/") ||
            path.substringAfterLast('/') in JUNK_FILE_NAMES

    @Throws(IOException::class)
    fun convert(source: Path, target: Path, options: Options = Options(), workers: ExecutorService? = null): Stats {
        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
        return write(target, attrs.size(), attrs.lastModifiedTime().toMillis(), options, workers) { sink ->
            if (Files.isDirectory(source)) feedDirectory(source, sink) else feedZip(source, sink)
        }
    }

    fun interface EntrySink {
        fun accept(path: String, data: ByteArray)
    }

    @Throws(IOException::class)
    fun write(
        target: Path,
        sourceSize: Long,
        sourceMtime: Long,
        options: Options,
        workers: ExecutorService?,
        feed: (EntrySink) -> Unit,
    ): Stats {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        val stats = Stats()
        try {
            var indexOffset: Long
            var entryCount: Int
            DataOutputStream(Files.newOutputStream(tmp).buffered(1 shl 16)).use { out ->
                writeHeaderPlaceholder(out, sourceSize, sourceMtime)
                val ctx = WriteContext(out, stats, options.deduplicate)
                val pipeline = Pipeline(ctx, options, workers)
                feed(EntrySink { path, data ->
                    if (isJunk(path)) stats.junkSkipped.incrementAndGet() else pipeline.submit(path, data)
                })
                pipeline.finish()
                indexOffset = ctx.offset
                writeIndex(out, ctx.entries)
                entryCount = ctx.entries.size
            }
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel ->
                val buf = ByteBuffer.allocate(12)
                buf.putLong(indexOffset)
                buf.putInt(entryCount)
                buf.flip()
                while (buf.hasRemaining()) channel.write(buf, 28L + buf.position())
                channel.force(false)
            }
            stats.entries.set(entryCount)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return stats
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private class IndexEntry(val path: String, val offset: Long, val size: Int)

    private class ShaKey(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ShaKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private class WriteContext(val out: DataOutputStream, val stats: Stats, val deduplicate: Boolean) {
        val entries = ArrayList<IndexEntry>(1024)
        var offset: Long = RocketFuelFormat.HEADER_SIZE.toLong()
        private val digest = MessageDigest.getInstance("SHA-256")
        private val seen = HashMap<ShaKey, IndexEntry>()

        fun add(path: String, data: ByteArray) {
            if (deduplicate && data.isNotEmpty()) {
                val key = ShaKey(digest.digest(data))
                val existing = seen[key]
                if (existing != null) {
                    entries += IndexEntry(path, existing.offset, existing.size)
                    stats.dedupedBytes.addAndGet(data.size.toLong())
                    return
                }
                val entry = IndexEntry(path, offset, data.size)
                out.write(data)
                offset += data.size
                entries += entry
                seen[key] = entry
                return
            }
            entries += IndexEntry(path, offset, data.size)
            out.write(data)
            offset += data.size
        }
    }

    private class Pipeline(
        private val ctx: WriteContext,
        private val options: Options,
        private val workers: ExecutorService?,
    ) {
        private class Pending(val path: String, val size: Int, val result: Future<ByteArray>?, val data: ByteArray?)

        private val window = ArrayDeque<Pending>()
        private var inFlight = 0L

        fun submit(path: String, data: ByteArray) {
            if (workers == null || !needsWork(path)) {
                window += Pending(path, data.size, null, if (needsWork(path)) optimizeEntry(path, data, options, ctx.stats) else data)
            } else {
                window += Pending(path, data.size, workers.submit<ByteArray> { optimizeEntry(path, data, options, ctx.stats) }, null)
            }
            inFlight += data.size
            while (inFlight > MAX_IN_FLIGHT_BYTES || window.size > MAX_IN_FLIGHT_ENTRIES) drainOne()
        }

        fun finish() {
            while (window.isNotEmpty()) drainOne()
        }

        private fun needsWork(path: String): Boolean =
            (options.jsonMinify && isJson(path)) || (options.optimizeTextures && path.endsWith(".png"))

        private fun drainOne() {
            val next = window.removeFirst()
            inFlight -= next.size
            val bytes = next.data ?: try {
                next.result!!.get()
            } catch (e: ExecutionException) {
                throw IOException("Optimierung von ${next.path} fehlgeschlagen", e.cause)
            }
            ctx.add(next.path, bytes)
        }
    }

    private fun isJson(path: String): Boolean = path.endsWith(".json") || path.endsWith(".mcmeta")

    fun optimizeEntry(path: String, data: ByteArray, options: Options, stats: Stats): ByteArray {
        if (options.jsonMinify && isJson(path)) {
            return minifyJson(data, stats)
        }
        if (options.optimizeTextures && path.endsWith(".png")) {
            val optimized = PngOptimizer.optimize(data, options.pngCompressionLevel.coerceIn(1, 9))
            if (optimized != null && optimized.size < data.size) {
                stats.pngBytesSaved.addAndGet((data.size - optimized.size).toLong())
                return optimized
            }
        }
        return data
    }

    fun minifyJson(data: ByteArray, stats: Stats): ByteArray {
        return try {
            val text = String(data, Charsets.UTF_8)
            val reader = JsonReader(StringReader(text))
            reader.strictness = Strictness.STRICT
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) return data
            val minified = gson.toJson(element).toByteArray(Charsets.UTF_8)
            if (minified.size < data.size) {
                stats.jsonBytesSaved.addAndGet((data.size - minified.size).toLong())
                minified
            } else {
                data
            }
        } catch (_: Exception) {
            data
        }
    }

    private fun writeHeaderPlaceholder(out: DataOutputStream, sourceSize: Long, sourceMtime: Long) {
        out.write(RocketFuelFormat.MAGIC)
        out.writeInt(RocketFuelFormat.VERSION)
        out.writeLong(sourceSize)
        out.writeLong(sourceMtime)
        out.writeLong(0L)
        out.writeInt(0)
        out.writeInt(RocketFuelFormat.WRITER_VERSION)
    }

    private fun feedZip(zip: Path, sink: EntrySink) {
        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (entry.size > Int.MAX_VALUE) throw IOException("Eintrag zu gross: ${entry.name}")
                sink.accept(entry.name, zf.getInputStream(entry).use { it.readBytes() })
            }
        }
    }

    private fun feedDirectory(dir: Path, sink: EntrySink) {
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                sink.accept(dir.relativize(file).joinToString("/"), Files.readAllBytes(file))
            }
        }
    }

    private fun writeIndex(out: DataOutputStream, index: List<IndexEntry>) {
        for (entry in index) {
            val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
            out.writeInt(pathBytes.size)
            out.write(pathBytes)
            out.writeLong(entry.offset)
            out.writeInt(entry.size)
        }
    }

    object PngOptimizer {
        private val SIGNATURE = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val KEEP = setOf("IHDR", "PLTE", "tRNS")
        private const val MAX_RAW_SIZE = 128 * 1024 * 1024

        fun optimize(data: ByteArray, level: Int = 6): ByteArray? {
            if (data.size < SIGNATURE.size + 12) return null
            for (i in SIGNATURE.indices) {
                if (data[i] != SIGNATURE[i]) return null
            }

            val kept = ArrayList<Pair<String, ByteArray>>()
            val idat = ByteArrayOutputStream()
            var pos = SIGNATURE.size
            var sawEnd = false
            while (pos + 12 <= data.size) {
                val len = readInt(data, pos)
                if (len < 0 || pos + 12 + len > data.size) return null
                val type = String(data, pos + 4, 4, Charsets.US_ASCII)
                when {
                    type == "IDAT" -> idat.write(data, pos + 8, len)
                    type == "IEND" -> {
                        sawEnd = true
                        break
                    }
                    type in KEEP -> kept += type to data.copyOfRange(pos + 8, pos + 8 + len)
                    type[0].isUpperCase() -> return null
                }
                pos += 12 + len
            }
            if (!sawEnd || idat.size() == 0 || kept.none { it.first == "IHDR" }) return null

            val raw = inflate(idat.toByteArray()) ?: return null
            val recompressed = deflate(raw, level)

            val out = ByteArrayOutputStream(data.size)
            out.write(SIGNATURE)
            for ((type, chunkData) in kept) writeChunk(out, type, chunkData)
            writeChunk(out, "IDAT", recompressed)
            writeChunk(out, "IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun inflate(compressed: ByteArray): ByteArray? {
            val inflater = Inflater()
            return try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream(compressed.size * 3)
                val buf = ByteArray(1 shl 16)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    out.write(buf, 0, n)
                    if (out.size() > MAX_RAW_SIZE) return null
                }
                out.toByteArray()
            } catch (_: Exception) {
                null
            } finally {
                inflater.end()
            }
        }

        private fun deflate(raw: ByteArray, level: Int): ByteArray {
            val deflater = Deflater(level)
            try {
                deflater.setInput(raw)
                deflater.finish()
                val out = ByteArrayOutputStream(raw.size / 3 + 64)
                val buf = ByteArray(1 shl 16)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            writeInt(out, data.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            writeInt(out, crc.value.toInt())
        }

        private fun readInt(data: ByteArray, pos: Int): Int =
            (data[pos].toInt() and 0xFF shl 24) or
                (data[pos + 1].toInt() and 0xFF shl 16) or
                (data[pos + 2].toInt() and 0xFF shl 8) or
                (data[pos + 3].toInt() and 0xFF)

        private fun writeInt(out: ByteArrayOutputStream, value: Int) {
            out.write(value ushr 24 and 0xFF)
            out.write(value ushr 16 and 0xFF)
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }
    }
}
