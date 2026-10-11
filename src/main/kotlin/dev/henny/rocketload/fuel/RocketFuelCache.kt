package dev.henny.rocketload.fuel

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.api.impl.RocketLoadApiImpl
import dev.henny.rocketload.client.ReloadDebug
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object RocketFuelCache {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private const val TOUCH_INTERVAL_MS = 6L * 60 * 60 * 1000

    private class Holder(val key: Path, val fuel: RocketFuelFile) {
        var refs = 0
    }

    val cacheDir: Path by lazy {
        val dir = FabricLoader.getInstance().gameDir.resolve("rocketload").resolve("cache")
        Files.createDirectories(dir)
        dir
    }

    private val byPath = HashMap<Path, Holder>()
    private val byFuel = IdentityHashMap<RocketFuelFile, Holder>()
    private val converting = ConcurrentHashMap.newKeySet<Path>()
    private val threadCounter = AtomicInteger()

    private val cores = Runtime.getRuntime().availableProcessors()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RocketLoad-Converter").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    private val workers: ExecutorService = Executors.newFixedThreadPool((cores / 2).coerceIn(1, 6)) { r ->
        Thread(r, "RocketLoad-Optimizer-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    private fun normalize(source: Path): Path = source.toAbsolutePath().normalize()

    fun acquire(source: Path): RocketFuelFile? {
        val key = normalize(source)
        if (key in converting) {
            ReloadDebug.log("fuel miss (converting): {}", key.fileName)
            return null
        }
        return try {
            val t0 = System.nanoTime()
            val attrs = Files.readAttributes(key, BasicFileAttributes::class.java)
            synchronized(this) {
                val existing = byPath[key]
                if (existing != null) {
                    if (isValidFor(existing.fuel, attrs)) {
                        existing.refs++
                        ReloadDebug.log("fuel hit open: {} refs={}", key.fileName, existing.refs)
                        return existing.fuel
                    }
                    byPath.remove(key)
                    if (existing.refs <= 0) closeHolder(existing)
                }
            }
            val target = cacheFile(key, attrs)
            if (!isDiskCacheValid(target, attrs)) {
                ReloadDebug.log("fuel miss disk: {}", key.fileName)
                return null
            }
            val fuel = RocketFuelFile.open(target)
            synchronized(this) {
                val existing = byPath[key]
                if (existing != null && isValidFor(existing.fuel, attrs)) {
                    fuel.close()
                    existing.refs++
                    return existing.fuel
                }
                val holder = Holder(key, fuel).also { it.refs = 1 }
                byPath[key] = holder
                byFuel[fuel] = holder
                ReloadDebug.log(
                    "fuel index {} entries={} {} ms open={}",
                    key.fileName, fuel.entries.size, (System.nanoTime() - t0) / 1_000_000, byPath.size,
                )
                fuel
            }
        } catch (e: IOException) {
            logger.warn("[RocketLoad] Cache fuer {} nicht nutzbar, Fallback auf Vanilla: {}", key.fileName, e.toString())
            null
        }
    }

    fun release(fuel: RocketFuelFile) {
        synchronized(this) {
            val holder = byFuel[fuel]
            if (holder == null) {
                runCatching { fuel.close() }
                return
            }
            if (--holder.refs > 0) return
            if (byPath[holder.key] === holder) byPath.remove(holder.key)
            closeHolder(holder)
        }
    }

    private fun closeHolder(holder: Holder) {
        byFuel.remove(holder.fuel)
        try {
            holder.fuel.close()
        } catch (e: Exception) {
            logger.warn("[RocketLoad] Fuel-Close fehlgeschlagen: {}", e.toString())
        }
    }

    fun scheduleConvert(source: Path) {
        val key = normalize(source)
        if (!converting.add(key)) return
        executor.execute {
            try {
                convertOnWorker(key)
            } catch (e: Exception) {
                logger.warn("[RocketLoad] Konvertierung von {} fehlgeschlagen: {}", key.fileName, e.toString())
            } finally {
                converting.remove(key)
            }
        }
    }

    fun cachedFile(source: Path): Path? {
        val key = normalize(source)
        return try {
            val attrs = Files.readAttributes(key, BasicFileAttributes::class.java)
            cacheFile(key, attrs).takeIf { isDiskCacheValid(it, attrs) }
        } catch (_: IOException) {
            null
        }
    }

    fun convertAsync(source: Path): CompletableFuture<Path> {
        val key = normalize(source)
        val result = CompletableFuture<Path>()
        executor.execute {
            val added = converting.add(key)
            try {
                convertOnWorker(key)
                val file = cachedFile(key) ?: throw IOException("Konvertierung lieferte keinen gueltigen Cache: ${key.fileName}")
                result.complete(file)
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            } finally {
                if (added) converting.remove(key)
            }
        }
        return result
    }

    fun optimizeAll(dir: Path, onProgress: ((done: Int, total: Int) -> Unit)?, onFinished: (converted: Int, ms: Long) -> Unit) {
        executor.execute {
            val start = System.nanoTime()
            val zips = collectZips(dir)
            var converted = 0
            zips.forEachIndexed { index, zip ->
                val key = normalize(zip)
                if (converting.add(key)) {
                    try {
                        if (convertOnWorker(key)) converted++
                    } catch (e: Exception) {
                        logger.warn("[RocketLoad] Konvertierung von {} fehlgeschlagen: {}", zip.fileName, e.toString())
                    } finally {
                        converting.remove(key)
                    }
                }
                onProgress?.invoke(index + 1, zips.size)
            }
            onFinished(converted, (System.nanoTime() - start) / 1_000_000)
        }
    }

    fun collectZips(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val out = ArrayList<Path>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val name = child.fileName.toString()
                    if (name.startsWith(".")) continue
                    if (name.endsWith(".zip", ignoreCase = true) && Files.isRegularFile(child)) out.add(child)
                }
            }
        } catch (e: IOException) {
            logger.warn("[RocketLoad] Pack-Scan fehlgeschlagen: {}", e.toString())
        }
        return out
    }

    fun housekeeping(retentionDays: Int) {
        executor.execute {
            try {
                var files = 0
                var bytes = 0L
                var deleted = 0
                val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
                Files.list(cacheDir).use { stream ->
                    for (file in stream.toList()) {
                        val name = file.fileName.toString()
                        if (!Files.isRegularFile(file)) continue
                        val isTmp = name.endsWith(".tmp")
                        val isFuel = name.endsWith(RocketFuelFormat.FILE_EXTENSION)
                        if (!isTmp && !isFuel) continue
                        val stale = isTmp || (retentionDays > 0 && Files.getLastModifiedTime(file).toMillis() < cutoff)
                        if (stale && tryDelete(file)) {
                            deleted++
                            continue
                        }
                        files++
                        bytes += Files.size(file)
                    }
                }
                logger.info(
                    "[RocketLoad] Cache: {} Datei(en), {} MiB{}",
                    files, bytes / (1024 * 1024),
                    if (deleted > 0) ", $deleted veraltete geloescht" else "",
                )
            } catch (e: IOException) {
                logger.warn("[RocketLoad] Cache-Housekeeping fehlgeschlagen: {}", e.toString())
            }
        }
    }

    private fun tryDelete(file: Path): Boolean = try {
        Files.deleteIfExists(file)
    } catch (_: IOException) {
        false
    }

    private fun writerOptions(): RocketFuelWriter.Options {
        val config = RocketLoadConfig.instance
        return RocketFuelWriter.Options(
            jsonMinify = config.jsonMinify,
            deduplicate = config.deduplicate,
            optimizeTextures = config.optimizeTextures,
            pngCompressionLevel = config.pngCompressionLevel,
        )
    }

    private fun convertOnWorker(key: Path): Boolean {
        val attrs = Files.readAttributes(key, BasicFileAttributes::class.java)
        synchronized(this) {
            val open = byPath[key]
            if (open != null && isValidFor(open.fuel, attrs)) return false
        }
        val target = cacheFile(key, attrs)
        if (isDiskCacheValid(target, attrs)) return false
        val start = System.nanoTime()
        val stats = RocketFuelWriter.convert(key, target, writerOptions(), workers)
        removeOutdatedSiblings(key, target)
        RocketLoadApiImpl.firePackConverted(key, target)
        logger.info(
            "[RocketLoad] {} konvertiert: {} Eintraege, {} ms | {} Junk-Dateien entfernt, {} KiB JSON gespart, {} KiB PNG gespart, {} KiB dedupliziert",
            key.fileName, stats.entries.get(), (System.nanoTime() - start) / 1_000_000,
            stats.junkSkipped.get(), stats.jsonBytesSaved.get() / 1024, stats.pngBytesSaved.get() / 1024,
            stats.dedupedBytes.get() / 1024,
        )
        return true
    }

    private fun removeOutdatedSiblings(key: Path, current: Path) {
        val prefix = cachePrefix(key)
        val openFiles = synchronized(this) { byFuel.keys.map { it.path.toAbsolutePath().normalize() }.toSet() }
        try {
            Files.newDirectoryStream(cacheDir, "$prefix*${RocketFuelFormat.FILE_EXTENSION}").use { stream ->
                for (file in stream) {
                    if (file == current) continue
                    if (file.toAbsolutePath().normalize() in openFiles) continue
                    tryDelete(file)
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun isValidFor(fuel: RocketFuelFile, attrs: BasicFileAttributes): Boolean =
        fuel.sourceSize == attrs.size() &&
            fuel.sourceMtime == attrs.lastModifiedTime().toMillis() &&
            fuel.writerVersion == RocketFuelFormat.WRITER_VERSION

    private fun isDiskCacheValid(target: Path, attrs: BasicFileAttributes): Boolean {
        if (!Files.isRegularFile(target)) return false
        return try {
            val header = RocketFuelFile.readHeader(target)
            val valid = header.sourceSize == attrs.size() &&
                header.sourceMtime == attrs.lastModifiedTime().toMillis() &&
                header.writerVersion == RocketFuelFormat.WRITER_VERSION
            if (valid) {
                val now = System.currentTimeMillis()
                if (now - Files.getLastModifiedTime(target).toMillis() > TOUCH_INTERVAL_MS) {
                    Files.setLastModifiedTime(target, FileTime.fromMillis(now))
                }
            }
            valid
        } catch (e: IOException) {
            logger.warn("[RocketLoad] Korrupte Cachedatei {} wird neu erstellt: {}", target.fileName, e.toString())
            false
        }
    }

    private fun sha(text: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }

    private fun cachePrefix(key: Path): String {
        val baseName = key.fileName.toString()
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64)
        return "$baseName-${sha(key.toString(), 16)}-"
    }

    private fun cacheFile(key: Path, attrs: BasicFileAttributes): Path {
        val stamp = sha("${attrs.size()}:${attrs.lastModifiedTime().toMillis()}:${RocketFuelFormat.WRITER_VERSION}", 8)
        return cacheDir.resolve("${cachePrefix(key)}$stamp${RocketFuelFormat.FILE_EXTENSION}")
    }
}
