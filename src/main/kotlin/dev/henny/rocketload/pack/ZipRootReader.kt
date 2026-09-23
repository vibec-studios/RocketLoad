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

    fun read(file: File, names: Set<String>): Map<String, ByteArray> {
        RandomAccessFile(file, "r").use { raf ->
            val eocd = findEocd(raf)
            val wanted = HashMap<String, ByteArray>(names.size)
            var offset = eocd.cdOffset
            var remaining = eocd.cdEntries
            while (remaining-- > 0 && wanted.size < names.size) {
                raf.seek(offset)
                if (readInt(raf) != CDIR) throw ZipException("Ungueltiger Central Directory")
                raf.skipBytes(6)
                val flags = readUnsignedShort(raf)
                val method = readUnsignedShort(raf)
                raf.skipBytes(8)
                var compressed = readInt(raf).toLong() and 0xFFFFFFFFL
                var uncompressed = readInt(raf).toLong() and 0xFFFFFFFFL
                val nameLen = readUnsignedShort(raf)
                val extraLen = readUnsignedShort(raf)
                val commentLen = readUnsignedShort(raf)
                raf.skipBytes(8)
                var localOffset = readInt(raf).toLong() and 0xFFFFFFFFL
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val extra = ByteArray(extraLen)
                if (extraLen > 0) raf.readFully(extra)
                raf.skipBytes(commentLen)
                offset = raf.filePointer
                val name = String(nameBytes, Charsets.UTF_8)
                if (name !in names) continue
                if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                    val zip64 = parseZip64Extra(extra)
                    if (zip64 != null) {
                        compressed = zip64.compressed
                        uncompressed = zip64.uncompressed
                        localOffset = zip64.localOffset
                    }
                }
                wanted[name] = readLocal(raf, localOffset, method, flags, compressed, uncompressed)
            }
            return wanted
        }
    }

    private data class Eocd(val cdOffset: Long, val cdEntries: Int)
    private data class Zip64(val uncompressed: Long, val compressed: Long, val localOffset: Long)

    private fun findEocd(raf: RandomAccessFile): Eocd {
        val len = raf.length()
        val max = minOf(len, 65535 + 22)
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
            return Eocd(cdOffset, diskEntries)
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
        raf.skipBytes(16)
        val entries = java.lang.Long.reverseBytes(raf.readLong())
        raf.skipBytes(8)
        val cdOffset = java.lang.Long.reverseBytes(raf.readLong())
        return Eocd(cdOffset, entries.toInt())
    }

    private fun parseZip64Extra(extra: ByteArray): Zip64? {
        var i = 0
        while (i + 4 <= extra.size) {
            val id = (extra[i].toInt() and 0xFF) or ((extra[i + 1].toInt() and 0xFF) shl 8)
            val size = (extra[i + 2].toInt() and 0xFF) or ((extra[i + 3].toInt() and 0xFF) shl 8)
            i += 4
            if (i + size > extra.size) break
            if (id == 1 && size >= 24) {
                return Zip64(u64(extra, i), u64(extra, i + 8), u64(extra, i + 16))
            }
            i += size
        }
        return null
    }

    private fun readLocal(
        raf: RandomAccessFile,
        localOffset: Long,
        method: Int,
        flags: Int,
        compressed: Long,
        uncompressed: Long,
    ): ByteArray {
        raf.seek(localOffset)
        if (readInt(raf) != LOCAL) throw ZipException("Local Header ungueltig")
        raf.skipBytes(22)
        val nameLen = readUnsignedShort(raf)
        val extraLen = readUnsignedShort(raf)
        raf.skipBytes(nameLen + extraLen)
        if (compressed > 8L * 1024 * 1024) throw ZipException("Root-Datei zu gross")
        val packed = ByteArray(compressed.toInt())
        raf.readFully(packed)
        if (method == 0) return packed
        if (method != 8) throw ZipException("Kompression $method nicht unterstuetzt")
        val inflater = Inflater(true)
        return try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream(uncompressed.coerceAtMost(2L * 1024 * 1024).toInt().coerceAtLeast(256))
            val buf = ByteArray(4096)
            if (flags and 0x8 != 0) {
                // data descriptor: nowrap inflater still works with packed bytes from CD size
            }
            while (!inflater.finished() && out.size() < 2 * 1024 * 1024) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw ZipException("ZIP Dictionary")
                    break
                }
                out.write(buf, 0, n)
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
