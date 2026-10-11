package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.api.ReloadDomain
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object ChunkRebuildGuard {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    @Volatile
    private var lastBlocks: String? = null

    private var inFinish = false

    private var bypass = false

    @JvmStatic
    fun captureBaseline(mc: Minecraft) {
        if (lastBlocks == null) lastBlocks = AtlasLayouts.blocksLayout(mc)
        ReloadSkips.capture(mc.resourceManager)
    }

    fun duringFinish(action: () -> Unit) {
        inFinish = true
        try {
            action()
        } finally {
            inFinish = false
        }
    }

    @JvmStatic
    fun interceptAllChanged(): Boolean {
        if (bypass || !inFinish) return false
        val mc = Minecraft.getInstance() ?: return false
        BackgroundReloadDriver.enqueueApply("chunk-guard") { decide(mc) }
        return true
    }

    private fun decide(mc: Minecraft) {
        val blocks = AtlasLayouts.blocksLayout(mc)
        val sameBlocks = lastBlocks != null && blocks != null && lastBlocks == blocks
        lastBlocks = blocks
        val config = RocketLoadConfig.instance
        if (!config.chunkRebuildGuard || (ReloadSkips.packSetChanged() && !config.selectivePackSetReload)) {
            full(mc, "Pack-Set geaendert oder Guard aus")
            return
        }
        val geometrySame = ReloadSkips.geometryUnchanged(mc.resourceManager)
        val visualsSkipped = ReloadSkips.decision(ReloadDomain.VISUALS) == true
        val colormapsSkipped = ReloadSkips.decision(ReloadDomain.COLORMAPS) != false
        ReloadDebug.log("chunk-guard sameBlocks={} geometrySame={} visualsSkipped={} colormapsSkipped={}", sameBlocks, geometrySame, visualsSkipped, colormapsSkipped)
        if (geometrySame && colormapsSkipped && (visualsSkipped || sameBlocks)) {
            logger.info("[RocketLoad] Geometrie und Block-Atlas unveraendert - kein Chunk-Rebuild")
            return
        }
        if (McCompat.softTerrainRebuild(mc)) {
            logger.info("[RocketLoad] Chunks werden im Hintergrund neu gebaut (alte Meshes bleiben sichtbar)")
            return
        }
        full(mc, "Geometrie/Atlas geaendert")
    }

    private fun full(mc: Minecraft, reason: String) {
        logger.info("[RocketLoad] Voller Chunk-Rebuild: {}", reason)
        bypass = true
        try {
            McCompat.fullTerrainRebuild(mc)
        } finally {
            bypass = false
        }
    }
}
