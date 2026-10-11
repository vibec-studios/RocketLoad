package dev.henny.rocketload.pack

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import java.util.zip.ZipException

object ZipRootReader {
    private const val EOCD = 0x06054b50L
    private const val CDIR = 0x02014b50
    private const val LOCAL = 0x04034b50
    private const val ZIP64_LOCATOR = 0x07064b50
    private const val ZIP64_EOCD = 0x06064b50
    private const val MAX_ENTRY = 2 * 1024 * 1024
    private const val MAX_CENTRAL_DIRECTORY = 64L * 1024 * 1024

    fun read(file: File, names: Set<String>): Map<String, ByteArray> {
        RandomAccessFile(file, "r").use { raf ->
            val eocd = findEocd(raf)
            if (eocd.cdSize > MAX_CENTRAL_DIRECTORY) throw ZipException("Central Directory zu gross")
            val cd = ByteArray(eocd.cdSize.toInt())
            raf.seek(eocd.cdOffset)
            raf.readFully(cd)
            val wanted = HashMap<String, ByteArray>(names.size)
            val nameBytes = names.associateBy { it.toByteArray(Charsets.UTF_8).toList() }
            val seen = HashSet<String>()
            var p = 0
            var remaining = eocd.cdEntries
            while (remaining-- > 0 && seen.size < names.size) {
                if (p + 46 > cd.size || u32(cd, p) != CDIR.toLong()) throw ZipException("Ungueltiger Central Directory")
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                var compressed = u32(cd, p + 20)
                var uncompressed = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                var localOffset = u32(cd, p + 42)
                val nameStart = p + 46
                if (nameStart + nameLen + extraLen > cd.size) throw ZipException("Ungueltiger Central Directory")
                p = nameStart + nameLen + extraLen + commentLen
                if (nameLen > 32) continue
                val name = nameBytes[cd.copyOfRange(nameStart, nameStart + nameLen).toList()] ?: continue
                if (!seen.add(name)) continue
                if (flags and 0x1 != 0) continue
                if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                    val extra = cd.copyOfRange(nameStart + nameLen, nameStart + nameLen + extraLen)
                    val zip64 = parseZip64Extra(extra, uncompressed, compressed, localOffset)
                    compressed = zip64.compressed
                    uncompressed = zip64.uncompressed
                    localOffset = zip64.localOffset
                }
                if (uncompressed > MAX_ENTRY || compressed > MAX_ENTRY * 4L) continue
                readLocal(raf, localOffset, method, compressed, uncompressed)?.let { wanted[name] = it }
            }
            return wanted
        }
    }

    private data class Eocd(val cdOffset: Long, val cdSize: Long, val cdEntries: Int)
    private data class Zip64(val uncompressed: Long, val compressed: Long, val localOffset: Long)

    private fun findEocd(raf: RandomAccessFile): Eocd {
        val len = raf.length()
        if (len < 22) throw ZipException("Datei zu klein")
        val max = minOf(len, 65535L + 22)
        val buf = ByteArray(max.toInt())
        raf.seek(len - max)
        raf.readFully(buf)
        for (i in buf.size - 22 downTo 0) {
            if (u32(buf, i) != EOCD) continue
            val diskEntries = u16(buf, i + 8)
            val cdSize = u32(buf, i + 12)
            val cdOffset = u32(buf, i + 16)
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || diskEntries == 0xFFFF) {
                return findZip64(raf, len - max + i)
            }
            if (cdOffset + cdSize > len) continue
            return Eocd(cdOffset, cdSize, diskEntries)
        }
        throw ZipException("EOCD nicht gefunden")
    }

    private fun findZip64(raf: RandomAccessFile, eocdPos: Long): Eocd {
        val locatorPos = eocdPos - 20
        if (locatorPos < 0) throw ZipException("ZIP64 Locator fehlt")
        raf.seek(locatorPos)
        if (readInt(raf) != ZIP64_LOCATOR) throw ZipException("ZIP64 Locator ungueltig")
        raf.skipBytes(4)
        val zip64Off = java.lang.Long.reverseBytes(raf.readLong())
        raf.seek(zip64Off)
        if (readInt(raf) != ZIP64_EOCD) throw ZipException("ZIP64 EOCD ungueltig")
        raf.skipBytes(20)
        val entries = java.lang.Long.reverseBytes(raf.readLong())
        raf.skipBytes(8)
        val cdSize = java.lang.Long.reverseBytes(raf.readLong())
        val cdOffset = java.lang.Long.reverseBytes(raf.readLong())
        if (entries > Int.MAX_VALUE) throw ZipException("Zu viele Eintraege")
        return Eocd(cdOffset, cdSize, entries.toInt())
    }

    private fun parseZip64Extra(extra: ByteArray, uncompressed: Long, compressed: Long, localOffset: Long): Zip64 {
        var i = 0
        while (i + 4 <= extra.size) {
            val id = u16(extra, i)
            val size = u16(extra, i + 2)
            i += 4
            if (i + size > extra.size) break
            if (id == 1) {
                var p = i
                val u = if (uncompressed == 0xFFFFFFFFL && p + 8 <= i + size) u64(extra, p).also { p += 8 } else uncompressed
                val c = if (compressed == 0xFFFFFFFFL && p + 8 <= i + size) u64(extra, p).also { p += 8 } else compressed
                val o = if (localOffset == 0xFFFFFFFFL && p + 8 <= i + size) u64(extra, p) else localOffset
                return Zip64(u, c, o)
            }
            i += size
        }
        return Zip64(uncompressed, compressed, localOffset)
    }

    private fun readLocal(raf: RandomAccessFile, localOffset: Long, method: Int, compressed: Long, uncompressed: Long): ByteArray? {
        raf.seek(localOffset)
        if (readInt(raf) != LOCAL) throw ZipException("Local Header ungueltig")
        raf.skipBytes(22)
        val nameLen = readUnsignedShort(raf)
        val extraLen = readUnsignedShort(raf)
        raf.skipBytes(nameLen + extraLen)
        val packed = ByteArray(compressed.toInt())
        raf.readFully(packed)
        if (method == 0) return packed
        if (method != 8) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream(uncompressed.toInt().coerceIn(256, MAX_ENTRY))
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_ENTRY) return null
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun readInt(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        if (b4 < 0) throw EOFException()
        return b1 or (b2 shl 8) or (b3 shl 16) or (b4 shl 24)
    }

    private fun readUnsignedShort(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        if (b2 < 0) throw EOFException()
        return b1 or (b2 shl 8)
    }

    private fun u16(buf: ByteArray, i: Int): Int =
        (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)

    private fun u32(buf: ByteArray, i: Int): Long =
        (u16(buf, i).toLong()) or (u16(buf, i + 2).toLong() shl 16)

    private fun u64(buf: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) {
            v = v or ((buf[i + k].toLong() and 0xFF) shl (8 * k))
        }
        return v
    }
}
