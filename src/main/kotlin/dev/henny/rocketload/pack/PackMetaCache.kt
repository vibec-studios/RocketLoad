package dev.henny.rocketload.pack

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

object PackMetaCache {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private const val MAGIC = "RLPM"
    private const val VERSION = 2
    private const val MAX_PNG = 2L * 1024 * 1024
    private val wanted = setOf("pack.mcmeta", "pack.png")

    class Entry(val size: Long, val mtime: Long, val mcmeta: ByteArray, val png: ByteArray?)

    private val memory = ConcurrentHashMap<Path, Entry>()
    private val threads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8)) { task ->
        Thread(task, "RocketLoad-PackMeta-${threads.incrementAndGet()}").apply { isDaemon = true }
    }

    private val diskDir: Path by lazy {
        val dir = FabricLoader.getInstance().gameDir.resolve("rocketload").resolve("cache").resolve("packmeta")
        Files.createDirectories(dir)
        dir
    }

    fun prefetchAsync(packsDir: Path) {
        Thread({
            val start = System.nanoTime()
            val n = prefetchBlocking(packsDir)
            logger.info("[RocketLoad] Pack-Metadaten vorbereitet: {} Datei(en) in {} ms", n, (System.nanoTime() - start) / 1_000_000)
        }, "RocketLoad-PackMeta-Prefetch").apply { isDaemon = true }.start()
    }

    fun prefetchBlocking(packsDir: Path): Int {
        if (!Files.isDirectory(packsDir)) return 0
        val jobs = ArrayList<Future<Entry?>>()
        try {
            Files.newDirectoryStream(packsDir).use { stream ->
                for (child in stream) {
                    val name = child.fileName.toString()
                    if (name.startsWith(".")) continue
                    val isZip = name.endsWith(".zip", true) && Files.isRegularFile(child)
                    val isDir = Files.isDirectory(child) && Files.isRegularFile(child.resolve("pack.mcmeta"))
                    if (isZip || isDir) jobs += executor.submit<Entry?> { ensure(child) }
                }
            }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Ordner nicht lesbar: {}", e.toString())
        }
        return jobs.count { runCatching { it.get() != null }.getOrDefault(false) }
    }

    fun openMetadataPack(file: File): Entry? = ensure(file.toPath())

    fun pngForPackId(packId: String): Pair<ByteArray?, Long>? {
        if (!packId.startsWith("file/")) return null
        val path = FabricLoader.getInstance().gameDir.resolve("resourcepacks").resolve(packId.substring(5))
        val entry = ensure(path) ?: return null
        return entry.png to (entry.size * 31 + entry.mtime)
    }

    private class Stamp(val size: Long, val mtime: Long)

    private fun stamp(abs: Path): Stamp {
        if (!Files.isDirectory(abs)) return Stamp(Files.size(abs), Files.getLastModifiedTime(abs).toMillis())
        var size = 0L
        var mtime = 0L
        for (name in wanted) {
            val file = abs.resolve(name)
            if (Files.isRegularFile(file)) {
                size = size * 1_000_003 + Files.size(file)
                mtime = mtime * 31 + Files.getLastModifiedTime(file).toMillis()
            }
        }
        return Stamp(size, mtime)
    }

    fun ensure(path: Path): Entry? {
        val abs = path.toAbsolutePath().normalize()
        return try {
            val stamp = stamp(abs)
            memory[abs]?.let { if (it.size == stamp.size && it.mtime == stamp.mtime) return it }
            readDisk(abs, stamp)?.also { memory[abs] = it }
                ?: loadFresh(abs, stamp)?.also {
                    memory[abs] = it
                    writeDisk(abs, it)
                }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Meta {} nicht lesbar: {}", abs.fileName, e.toString())
            null
        }
    }

    private fun loadFresh(path: Path, stamp: Stamp): Entry? {
        val mcmeta: ByteArray
        val png: ByteArray?
        if (Files.isDirectory(path)) {
            val metaFile = path.resolve("pack.mcmeta")
            if (!Files.isRegularFile(metaFile)) return null
            mcmeta = Files.readAllBytes(metaFile)
            val pngFile = path.resolve("pack.png")
            png = if (Files.isRegularFile(pngFile) && Files.size(pngFile) <= MAX_PNG) Files.readAllBytes(pngFile) else null
        } else {
            val file = path.toFile()
            val roots = try {
                ZipRootReader.read(file, wanted)
            } catch (_: Exception) {
                zipFallback(file)
            }
            mcmeta = roots["pack.mcmeta"] ?: return null
            png = roots["pack.png"]
        }
        return Entry(stamp.size, stamp.mtime, mcmeta, png)
    }

    private fun zipFallback(file: File): Map<String, ByteArray> {
        ZipFile(file).use { zip ->
            val out = HashMap<String, ByteArray>()
            for (name in wanted) {
                val entry = zip.getEntry(name) ?: continue
                if (entry.size > MAX_PNG) continue
                zip.getInputStream(entry).use { stream -> out[name] = stream.readBytes() }
            }
            return out
        }
    }

    private fun diskFile(path: Path): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toString().toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }.take(16)
        val name = path.fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        return diskDir.resolve("$name-$key.rlmeta")
    }

    private fun readDisk(path: Path, stamp: Stamp): Entry? {
        val file = diskFile(path)
        if (!Files.isRegularFile(file)) return null
        return try {
            DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readUTF() != MAGIC || input.readInt() != VERSION) return null
                if (input.readUTF() != path.toString()) return null
                if (input.readLong() != stamp.size || input.readLong() != stamp.mtime) return null
                val metaLen = input.readInt()
                if (metaLen !in 0..MAX_PNG.toInt()) return null
                val meta = ByteArray(metaLen).also { input.readFully(it) }
                val pngLen = input.readInt()
                if (pngLen !in 0..MAX_PNG.toInt()) return null
                val png = if (pngLen > 0) ByteArray(pngLen).also { input.readFully(it) } else null
                Entry(stamp.size, stamp.mtime, meta, png)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(path: Path, entry: Entry) {
        try {
            DataOutputStream(Files.newOutputStream(diskFile(path)).buffered()).use { out ->
                out.writeUTF(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(path.toString())
                out.writeLong(entry.size)
                out.writeLong(entry.mtime)
                out.writeInt(entry.mcmeta.size)
                out.write(entry.mcmeta)
                out.writeInt(entry.png?.size ?: 0)
                entry.png?.let { out.write(it) }
            }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Meta-Cache nicht schreibbar: {}", e.toString())
        }
    }

    fun housekeeping(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
        executor.execute {
            try {
                Files.list(diskDir).use { stream ->
                    for (file in stream.toList()) {
                        if (!file.fileName.toString().endsWith(".rlmeta")) continue
                        if (Files.getLastModifiedTime(file).toMillis() < cutoff) runCatching { Files.deleteIfExists(file) }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
