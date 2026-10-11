package dev.henny.rocketload.fuel

import java.io.InputStream
import java.nio.ByteBuffer

class FuelEntryStream(val fuel: RocketFuelFile, val entry: RocketFuelFile.Entry) : InputStream() {
    private var data: ByteArray? = null
    private var pos = 0
    private var mark = 0

    val untouched: Boolean get() = data == null

    val size: Int get() = entry.size

    fun readInto(target: ByteBuffer) {
        fuel.read(entry, target)
        pos = entry.size
        data = EMPTY
    }

    private fun bytes(): ByteArray = data ?: fuel.readBytes(entry).also { data = it }

    override fun read(): Int {
        val bytes = bytes()
        return if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val bytes = bytes()
        if (pos >= bytes.size) return -1
        val n = minOf(len, bytes.size - pos)
        System.arraycopy(bytes, pos, b, off, n)
        pos += n
        return n
    }

    override fun readAllBytes(): ByteArray {
        val bytes = bytes()
        val result = if (pos == 0) bytes.copyOf() else bytes.copyOfRange(pos, bytes.size)
        pos = bytes.size
        return result
    }

    override fun skip(n: Long): Long {
        val bytes = bytes()
        val skipped = minOf(n.coerceAtLeast(0), (bytes.size - pos).toLong()).toInt()
        pos += skipped
        return skipped.toLong()
    }

    override fun available(): Int = if (data == null) entry.size else data!!.size - pos

    override fun markSupported(): Boolean = true

    override fun mark(readlimit: Int) {
        mark = pos
    }

    override fun reset() {
        pos = mark
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
