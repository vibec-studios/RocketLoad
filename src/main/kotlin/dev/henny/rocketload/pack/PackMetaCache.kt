package dev.henny.rocketload.pack

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourcePackInfo
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

object PackMetaCache {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private const val MAGIC = "RLPM"
    private const val VERSION = 1
    private val wanted = setOf("pack.mcmeta", "pack.png")

    data class Entry(val size: Long, val mtime: Long, val mcmeta: ByteArray, val png: ByteArray?)

    private val memory = ConcurrentHashMap<Path, Entry>()
    private val threads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(8) { task ->
        Thread(task, "RocketLoad-PackMeta-${threads.incrementAndGet()}").apply { isDaemon = true }
    }

    private val diskDir: Path by lazy {
        val dir = FabricLoader.getInstance().gameDir.resolve("rocketload").resolve("cache").resolve("packmeta")
        Files.createDirectories(dir)
        dir
    }

    fun prefetchAsync(packsDir: Path) {
        executor.execute {
            val start = System.nanoTime()
            val n = prefetchBlocking(packsDir)
            logger.info(
                "[RocketLoad] Pack-Metadaten vorbereitet: {} Datei(en) in {} ms",
                n,
                (System.nanoTime() - start) / 1_000_000,
            )
        }
    }

    fun prefetchBlocking(packsDir: Path): Int {
        if (!Files.isDirectory(packsDir)) return 0
        val jobs = ArrayList<Future<*>>()
        Files.newDirectoryStream(packsDir).use { stream ->
            for (child in stream) {
                val name = child.fileName.toString()
                if (name.startsWith(".")) continue
                if (Files.isRegularFile(child) && name.endsWith(".zip", true)) {
                    jobs += executor.submit<Entry?> { ensure(child) }
                } else if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("pack.mcmeta"))) {
                    jobs += executor.submit<Entry?> { ensure(child) }
                }
            }
        }
        var ok = 0
        for (job in jobs) {
            try {
                if (job.get() != null) ok++
            } catch (_: Exception) {
            }
        }
        return ok
    }

    fun openListPack(file: File, info: ResourcePackInfo): ResourcePack? {
        val entry = ensure(file.toPath()) ?: return null
        return PackListResources(info, entry.mcmeta, entry.png)
    }

    fun png(packId: String): ByteArray? {
        val relative = if (packId.startsWith("file/")) packId.substring(5) else packId
        val path = FabricLoader.getInstance().gameDir.resolve("resourcepacks").resolve(relative)
        return ensure(path)?.png
    }

    fun ensure(path: Path): Entry? {
        val abs = path.toAbsolutePath().normalize()
        return try {
            val size = Files.size(abs)
            val mtime = Files.getLastModifiedTime(abs).toMillis()
            memory[abs]?.let { if (it.size == size && it.mtime == mtime) return it }
            readDisk(abs, size, mtime)?.also { memory[abs] = it }
                ?: loadFresh(abs, size, mtime)?.also {
                    memory[abs] = it
                    writeDisk(abs, it)
                }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Meta {} nicht lesbar: {}", abs.fileName, e.toString())
            null
        }
    }

    private fun loadFresh(path: Path, size: Long, mtime: Long): Entry? {
        val mcmeta: ByteArray
        val png: ByteArray?
        if (Files.isDirectory(path)) {
            val metaFile = path.resolve("pack.mcmeta")
            if (!Files.isRegularFile(metaFile)) return null
            mcmeta = Files.readAllBytes(metaFile)
            val pngFile = path.resolve("pack.png")
            png = if (Files.isRegularFile(pngFile) && Files.size(pngFile) <= 2L * 1024 * 1024) {
                Files.readAllBytes(pngFile)
            } else {
                null
            }
        } else {
            val file = path.toFile()
            val roots = try {
                ZipRootReader.read(file, wanted)
            } catch (_: Exception) {
                zipFallback(file)
            }
            mcmeta = roots["pack.mcmeta"] ?: return null
            png = roots["pack.png"]?.takeIf { it.size <= 2 * 1024 * 1024 }
        }
        return Entry(size, mtime, mcmeta, png)
    }

    private fun zipFallback(file: File): Map<String, ByteArray> {
        ZipFile(file).use { zip ->
            val out = HashMap<String, ByteArray>()
            for (name in wanted) {
                val entry = zip.getEntry(name) ?: continue
                if (entry.size > 2L * 1024 * 1024) continue
                zip.getInputStream(entry).use { stream ->
                    out[name] = stream.readBytes()
                }
            }
            return out
        }
    }

    private fun diskFile(path: Path): Path {
        val key = path.toString().hashCode().toUInt().toString(16)
        val name = path.fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        return diskDir.resolve("$name-$key.rlmeta")
    }

    private fun readDisk(path: Path, size: Long, mtime: Long): Entry? {
        val file = diskFile(path)
        if (!Files.isRegularFile(file)) return null
        return try {
            DataInputStream(Files.newInputStream(file)).use { input ->
                val magic = input.readUTF()
                val version = input.readInt()
                if (magic != MAGIC || version != VERSION) return null
                val storedSize = input.readLong()
                val storedMtime = input.readLong()
                if (storedSize != size || storedMtime != mtime) return null
                val meta = ByteArray(input.readInt())
                input.readFully(meta)
                val pngLen = input.readInt()
                val png = if (pngLen > 0) ByteArray(pngLen).also { input.readFully(it) } else null
                Entry(size, mtime, meta, png)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(path: Path, entry: Entry) {
        try {
            DataOutputStream(Files.newOutputStream(diskFile(path))).use { out ->
                out.writeUTF(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(entry.size)
                out.writeLong(entry.mtime)
                out.writeInt(entry.mcmeta.size)
                out.write(entry.mcmeta)
                out.writeInt(entry.png?.size ?: 0)
                if (entry.png != null) out.write(entry.png)
            }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Meta-Cache nicht schreibbar: {}", e.toString())
        }
    }
}
