package dev.henny.rocketload.fuel

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object RocketFuelCache {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private class Holder(val fuel: RocketFuelFile) {
        val refs = AtomicInteger(0)
    }

    private val cacheDir: Path by lazy {
        val dir = FabricLoader.getInstance().gameDir.resolve("rocketload").resolve("cache")
        Files.createDirectories(dir)
        logger.info("[RocketLoad] Cache-Verzeichnis: {}", dir)
        dir
    }

    private val open = HashMap<Path, Holder>()
    private val converting = ConcurrentHashMap.newKeySet<Path>()
    private val threadCounter = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(1) { r ->
        Thread(r, "RocketLoad-Converter-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun get(source: Path): RocketFuelFile? = acquire(source)

    fun acquire(source: Path): RocketFuelFile? {
        if (converting.contains(source)) {
            dev.henny.rocketload.client.ReloadDebug.log("fuel miss (converting): {}", source.fileName)
            return null
        }
        return try {
            val t0 = System.nanoTime()
            val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
            synchronized(this) {
                val existing = open[source]
                if (existing != null && isValidFor(existing.fuel, attrs)) {
                    existing.refs.incrementAndGet()
                    dev.henny.rocketload.client.ReloadDebug.log(
                        "fuel hit open: {} refs={} ({} ms)",
                        source.fileName,
                        existing.refs.get(),
                        (System.nanoTime() - t0) / 1_000_000,
                    )
                    return existing.fuel
                }
                existing?.fuel?.close()
                open.remove(source)
            }
            if (!isDiskCacheValid(cacheDir.resolve(cacheFileName(source)), attrs)) {
                dev.henny.rocketload.client.ReloadDebug.log("fuel miss disk: {} ({} ms)", source.fileName, (System.nanoTime() - t0) / 1_000_000)
                return null
            }
            val fuel = RocketFuelFile.open(cacheDir.resolve(cacheFileName(source)))
            synchronized(this) {
                val existing = open[source]
                if (existing != null && isValidFor(existing.fuel, attrs)) {
                    fuel.close()
                    existing.refs.incrementAndGet()
                    return existing.fuel
                }
                existing?.fuel?.close()
                open[source] = Holder(fuel).also { it.refs.set(1) }
                logger.info(
                    "[RocketLoad] {} Index geladen ({} Eintraege, kein Full-MMap, {} offen)",
                    source.fileName,
                    fuel.entries.size,
                    open.size,
                )
                dev.henny.rocketload.client.ReloadDebug.log(
                    "fuel index {} entries={} {} ms open={}",
                    source.fileName,
                    fuel.entries.size,
                    (System.nanoTime() - t0) / 1_000_000,
                    open.size,
                )
                fuel
            }
        } catch (e: IOException) {
            logger.warn("[RocketLoad] Cache fuer {} nicht nutzbar, Fallback auf Vanilla: {}", source, e.toString())
            null
        }
    }

    fun release(fuel: RocketFuelFile) {
        synchronized(this) {
            val found = open.entries.firstOrNull { it.value.fuel === fuel }
            if (found == null) {
                try {
                    fuel.close()
                } catch (_: Exception) {
                }
                return
            }
            if (found.value.refs.decrementAndGet() > 0) return
            open.remove(found.key)
            try {
                found.value.fuel.close()
            } catch (e: Exception) {
                logger.warn("[RocketLoad] Fuel-Close fehlgeschlagen: {}", e.toString())
            }
            logger.info("[RocketLoad] {} aus RAM genommen ({} bleiben offen)", found.key.fileName, open.size)
        }
    }

    fun scheduleConvert(source: Path, onDone: ((converted: Boolean) -> Unit)? = null) {
        if (!converting.add(source)) return
        executor.execute {
            try {
                val converted = convertOnWorker(source)
                onDone?.invoke(converted)
            } catch (e: Exception) {
                logger.warn("[RocketLoad] Konvertierung von {} fehlgeschlagen: {}", source, e.toString())
                onDone?.invoke(false)
            } finally {
                converting.remove(source)
            }
        }
    }

    fun preconvertAll(dir: Path, onFinished: (converted: Int, ms: Long) -> Unit) {
        optimizeAll(dir, null, onFinished)
    }

    fun optimizeAll(dir: Path, onProgress: ((done: Int, total: Int) -> Unit)?, onFinished: (converted: Int, ms: Long) -> Unit) {
        executor.execute {
            val start = System.nanoTime()
            val zips = collectZips(dir)
            if (zips.isEmpty()) {
                onFinished(0, (System.nanoTime() - start) / 1_000_000)
                return@execute
            }
            var converted = 0
            zips.forEachIndexed { index, zip ->
                try {
                    if (convertOnWorker(zip)) converted++
                } catch (e: Exception) {
                    logger.warn("[RocketLoad] Konvertierung von {} fehlgeschlagen: {}", zip, e.toString())
                }
                onProgress?.invoke(index + 1, zips.size)
            }
            onFinished(converted, (System.nanoTime() - start) / 1_000_000)
        }
    }

    fun collectZips(dir: Path): List<Path> {
        val out = ArrayList<Path>()
        collectZips(dir, 0, out)
        return out
    }

    private fun collectZips(dir: Path, depth: Int, out: MutableList<Path>) {
        if (depth > 8 || !Files.isDirectory(dir)) return
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val name = child.fileName.toString()
                    if (name.startsWith(".")) continue
                    try {
                        if (Files.isRegularFile(child) && name.endsWith(".zip", ignoreCase = true)) {
                            out.add(child)
                        } else if (Files.isDirectory(child) && !Files.isRegularFile(child.resolve("pack.mcmeta"))) {
                            collectZips(child, depth + 1, out)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: IOException) {
            logger.warn("[RocketLoad] Pack-Scan fehlgeschlagen: {}", e.toString())
        }
    }

    fun housekeeping(retentionDays: Int) {
        executor.execute {
            try {
                var files = 0
                var bytes = 0L
                var deleted = 0
                val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
                Files.list(cacheDir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(RocketFuelFormat.FILE_EXTENSION) }
                        .forEach { file ->
                            if (retentionDays > 0 && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                                try {
                                    Files.delete(file)
                                    deleted++
                                    return@forEach
                                } catch (e: IOException) {
                                    logger.warn("[RocketLoad] Konnte {} nicht aufraeumen: {}", file.fileName, e.toString())
                                }
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

    private fun convertOnWorker(source: Path): Boolean {
        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
        val target = cacheDir.resolve(cacheFileName(source))
        synchronized(this) {
            if (open.containsKey(source)) return false
        }
        if (isDiskCacheValid(target, attrs)) return false
        val start = System.nanoTime()
        val stats = RocketFuelWriter.convert(source, target)
        val ms = (System.nanoTime() - start) / 1_000_000
        logger.info(
            "[RocketLoad] {} konvertiert: {} Eintraege, {} ms | {} Junk-Dateien entfernt, {} KiB JSON gespart, {} KiB PNG gespart, {} KiB dedupliziert (nicht im RAM gehalten)",
            source.fileName, stats.entries, ms,
            stats.junkSkipped, stats.jsonBytesSaved / 1024, stats.pngBytesSaved / 1024, stats.dedupedBytes / 1024,
        )
        return true
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
                Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()))
            }
            valid
        } catch (e: IOException) {
            logger.warn("[RocketLoad] Korrupte Cachedatei {} wird neu erstellt: {}", target.fileName, e.toString())
            false
        }
    }

    private fun cacheFileName(source: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.toAbsolutePath().toString().toByteArray(Charsets.UTF_8))
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        val baseName = source.fileName.toString()
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64)
        return "$baseName-$hash${RocketFuelFormat.FILE_EXTENSION}"
    }
}
