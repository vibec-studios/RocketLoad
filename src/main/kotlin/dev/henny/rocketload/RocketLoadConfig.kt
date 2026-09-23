package dev.henny.rocketload

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class RocketLoadConfig {
    var backgroundReload: Boolean = true
    var applyBudgetMsPerTick: Int = 3
    var toasts: Boolean = true
    var chunkRebuildGuard: Boolean = true
    var selectiveReload: Boolean = true
    var optimizeTextures: Boolean = true
    var warmupConversion: Boolean = true
    var jsonMinify: Boolean = true
    var deduplicate: Boolean = true
    var cacheRetentionDays: Int = 14
    var debugTimings: Boolean = false
    var pauseIntegratedServerDuringReload: Boolean = true
    var closePackScreenImmediately: Boolean = true
    var skipUnchangedSprites: Boolean = false
    var spreadTerrainRebuilds: Boolean = false
    var chunkRebuildsPerFrame: Int = 48

    companion object {
        private val logger = LoggerFactory.getLogger("RocketLoad")
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        private val path: Path by lazy {
            FabricLoader.getInstance().configDir.resolve("rocketload.json")
        }

        @JvmStatic
        val instance: RocketLoadConfig by lazy { load() }

        private fun load(): RocketLoadConfig {
            val config = try {
                if (Files.isRegularFile(path)) {
                    Files.newBufferedReader(path).use { gson.fromJson(it, RocketLoadConfig::class.java) }
                        ?: RocketLoadConfig()
                } else {
                    RocketLoadConfig()
                }
            } catch (e: Exception) {
                logger.warn("[RocketLoad] Config nicht lesbar, nutze Defaults: {}", e.toString())
                RocketLoadConfig()
            }
            config.skipUnchangedSprites = false
            config.spreadTerrainRebuilds = false
            try {
                Files.createDirectories(path.parent)
                Files.newBufferedWriter(path).use { gson.toJson(config, it) }
            } catch (e: Exception) {
                logger.warn("[RocketLoad] Config nicht schreibbar: {}", e.toString())
            }
            return config
        }
    }
}
