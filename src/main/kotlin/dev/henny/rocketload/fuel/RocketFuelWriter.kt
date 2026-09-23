package dev.henny.rocketload.fuel

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
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
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipFile

object RocketFuelWriter {

    class Stats {
        var entries: Int = 0
        var junkSkipped: Int = 0
        var jsonBytesSaved: Long = 0
        var pngBytesSaved: Long = 0
        var dedupedBytes: Long = 0
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val JUNK_FILE_NAMES = setOf(".DS_Store", "Thumbs.db", "desktop.ini", ".gitignore", ".gitattributes")

    private fun isJunk(path: String): Boolean =
        path.startsWith("__MACOSX/") ||
            path.startsWith(".git/") || path.contains("/.git/") ||
            path.substringAfterLast('/') in JUNK_FILE_NAMES

    @Throws(IOException::class)
    fun convert(source: Path, target: Path): Stats {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        val stats = Stats()
        try {
            val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                DataOutputStream(Files.newOutputStream(tmp).buffered(1 shl 16)).use { out ->
                    writeHeaderPlaceholder(out, attrs)
                    val ctx = WriteContext(out, stats)
                    when {
                        Files.isDirectory(source) -> writeDirectoryEntries(source, ctx)
                        else -> writeZipEntries(source, ctx)
                    }
                    val indexOffset = ctx.offset
                    writeIndex(out, ctx.entries)
                    out.flush()
                    patchHeader(channel, indexOffset, ctx.entries.size)
                    stats.entries = ctx.entries.size
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return stats
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private class IndexEntry(val path: String, val offset: Long, val size: Int)

    private class ShaKey(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ShaKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private class WriteContext(val out: DataOutputStream, val stats: Stats) {
        val entries = ArrayList<IndexEntry>(1024)
        var offset: Long = RocketFuelFormat.HEADER_SIZE.toLong()
        private val digest = MessageDigest.getInstance("SHA-256")
        private val seen = HashMap<ShaKey, IndexEntry>()

        fun add(path: String, data: ByteArray) {
            if (dev.henny.rocketload.RocketLoadConfig.instance.deduplicate) {
                val key = ShaKey(digest.digest(data))
                val existing = seen[key]
                if (existing != null) {
                    entries += IndexEntry(path, existing.offset, existing.size)
                    stats.dedupedBytes += data.size
                    return
                }
                val entry = IndexEntry(path, offset, data.size)
                out.write(data)
                offset += data.size
                entries += entry
                seen[key] = entry
                return
            }
            val entry = IndexEntry(path, offset, data.size)
            out.write(data)
            offset += data.size
            entries += entry
        }
    }

    private fun optimizeEntry(path: String, data: ByteArray, stats: Stats): ByteArray {
        val config = dev.henny.rocketload.RocketLoadConfig.instance
        if (config.jsonMinify && (path.endsWith(".json") || path.endsWith(".mcmeta"))) {
            return minifyJson(data, stats)
        }
        if (config.optimizeTextures && path.endsWith(".png")) {
            val optimized = PngOptimizer.optimize(data)
            if (optimized != null && optimized.size < data.size) {
                stats.pngBytesSaved += data.size - optimized.size
                return optimized
            }
        }
        return data
    }

    private fun minifyJson(data: ByteArray, stats: Stats): ByteArray {
        return try {
            val text = String(data, Charsets.UTF_8)
            val reader = JsonReader(StringReader(text))
            reader.strictness = com.google.gson.Strictness.STRICT
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return data
            val minified = gson.toJson(element).toByteArray(Charsets.UTF_8)
            if (minified.size < data.size) {
                stats.jsonBytesSaved += data.size - minified.size
                minified
            } else {
                data
            }
        } catch (_: Exception) {
            data
        }
    }

    private fun writeHeaderPlaceholder(out: DataOutputStream, attrs: BasicFileAttributes) {
        out.write(RocketFuelFormat.MAGIC)
        out.writeInt(RocketFuelFormat.VERSION)
        out.writeLong(attrs.size())
        out.writeLong(attrs.lastModifiedTime().toMillis())
        out.writeLong(0L)
        out.writeInt(0)
        out.writeInt(RocketFuelFormat.WRITER_VERSION)
    }

    private fun patchHeader(channel: FileChannel, indexOffset: Long, entryCount: Int) {
        val buf = ByteBuffer.allocate(12)
        buf.putLong(indexOffset)
        buf.putInt(entryCount)
        buf.flip()
        channel.write(buf, 28L)
        channel.force(false)
    }

    private fun writeZipEntries(zip: Path, ctx: WriteContext) {
        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (isJunk(entry.name)) {
                    ctx.stats.junkSkipped++
                    continue
                }
                if (entry.size > Int.MAX_VALUE) throw IOException("Eintrag zu gross: ${entry.name}")
                val data = zf.getInputStream(entry).use { it.readBytes() }
                ctx.add(entry.name, optimizeEntry(entry.name, data, ctx.stats))
            }
        }
    }

    private fun writeDirectoryEntries(dir: Path, ctx: WriteContext) {
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                val rel = dir.relativize(file).joinToString("/")
                if (isJunk(rel)) {
                    ctx.stats.junkSkipped++
                    return@forEach
                }
                val data = Files.readAllBytes(file)
                ctx.add(rel, optimizeEntry(rel, data, ctx.stats))
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

    private object PngOptimizer {
        private val SIGNATURE = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val KEEP = setOf("IHDR", "PLTE", "tRNS")
        private const val MAX_RAW_SIZE = 128 * 1024 * 1024

        fun optimize(data: ByteArray): ByteArray? {
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
                        pos += 12 + len
                        break
                    }
                    type in KEEP -> kept += type to data.copyOfRange(pos + 8, pos + 8 + len)
                }
                pos += 12 + len
            }
            if (!sawEnd || idat.size() == 0 || kept.none { it.first == "IHDR" }) return null

            val raw = inflate(idat.toByteArray()) ?: return null
            val recompressed = deflate(raw)

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
                    if (n == 0 && inflater.needsInput()) return null
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

        private fun deflate(raw: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
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
