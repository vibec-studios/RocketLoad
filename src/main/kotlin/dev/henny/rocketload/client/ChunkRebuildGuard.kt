package dev.henny.rocketload.client

import dev.henny.rocketload.fuel.RocketFuelFile
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.WorldRenderer
import net.minecraft.resource.ResourcePack
import org.slf4j.LoggerFactory

object ChunkRebuildGuard {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    @Volatile
    private var lastGeometry: List<String>? = null

    @Volatile
    private var lastBlocks: String? = null

    @JvmStatic
    fun register(pack: ResourcePack, fuel: RocketFuelFile) = ResourceFingerprints.register(pack, fuel)

    @JvmStatic
    fun captureBaseline(client: MinecraftClient) {
        if (lastGeometry == null) {
            lastGeometry = geometryFingerprint(client)
            lastBlocks = AtlasLayouts.blocksLayoutGpu(client)
            ReloadSkips.rememberBlocksLayout(lastBlocks)
        }
        ReloadSkips.capture(client.resourceManager)
    }

    @JvmStatic
    fun maybeReload(client: MinecraftClient, worldRenderer: WorldRenderer) {
        BackgroundReloadDriver.enqueueApply { reloadNow(client, worldRenderer) }
    }

    private fun reloadNow(client: MinecraftClient, worldRenderer: WorldRenderer) {
        if (!dev.henny.rocketload.RocketLoadConfig.instance.chunkRebuildGuard) {
            worldRenderer.reload()
            return
        }
        val t0 = System.nanoTime()
        val geometry = geometryFingerprint(client)
        val blocks = AtlasLayouts.blocksLayoutGpu(client)
        val sameGeometry = lastGeometry != null && lastGeometry == geometry
        val sameBlocks = lastBlocks != null && blocks != null && lastBlocks == blocks
        ReloadDebug.log(
            "chunk-guard {} ms sameGeo={} sameBlocks={} blocks={}",
            (System.nanoTime() - t0) / 1_000_000,
            sameGeometry,
            sameBlocks,
            blocks,
        )
        lastGeometry = geometry
        lastBlocks = blocks
        ReloadSkips.rememberBlocksLayout(blocks)

        if (ReloadSkips.packSetChanged()) {
            logger.info("[RocketLoad] Pack-Set geaendert - voller WorldRenderer-Reload")
            TerrainRebuildQueue.clear()
            worldRenderer.reload()
            return
        }
        if (!ReloadSkips.skippedModels()) {
            logger.info("[RocketLoad] Modelle neu gebacken - Chunks neu einplanen")
            TerrainRebuildQueue.clear()
            worldRenderer.scheduleTerrainUpdate()
            return
        }
        if (sameGeometry && sameBlocks) {
            logger.info("[RocketLoad] Block-Atlas-UVs unveraendert - kein Chunk-Rebuild")
            return
        }
        logger.info("[RocketLoad] Chunks neu einplanen (kein Full-Reload)")
        TerrainRebuildQueue.clear()
        worldRenderer.scheduleTerrainUpdate()
    }

    private fun geometryFingerprint(client: MinecraftClient): List<String> =
        ResourceFingerprints.fingerprint(
            client.resourceManager.streamResourcePacks().toList(),
            ResourceFingerprints::touchesGeometry,
        )
}
