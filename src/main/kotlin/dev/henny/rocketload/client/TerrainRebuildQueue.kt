package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.mixin.WorldRendererAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.render.chunk.ChunkBuilder
import org.slf4j.LoggerFactory
import java.util.ArrayDeque

object TerrainRebuildQueue {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private val pending = ArrayDeque<ChunkBuilder.BuiltChunk>()

    @JvmStatic
    fun clear() {
        pending.clear()
    }

    @JvmStatic
    fun schedule(worldRenderer: WorldRenderer) {
        pending.clear()
        val storage = (worldRenderer as WorldRendererAccessor).rocketload_getChunks() ?: return
        val chunks = storage.chunks ?: return
        val client = MinecraftClient.getInstance() ?: return
        val cam = client.gameRenderer.camera.cameraPos
        val sorted = chunks.filterNotNull().sortedBy { chunk ->
            val origin = chunk.origin
            cam.squaredDistanceTo(origin.x + 8.0, origin.y + 8.0, origin.z + 8.0)
        }
        pending.addAll(sorted)
        logger.info("[RocketLoad] Chunk-Rebuild in {} Sections, kamera-nah zuerst", pending.size)
        ReloadDebug.log("terrain queue {}", pending.size)
    }

    @JvmStatic
    fun pump() {
        if (pending.isEmpty()) return
        val n = RocketLoadConfig.instance.chunkRebuildsPerFrame.coerceIn(1, 256)
        var ran = 0
        while (ran < n && pending.isNotEmpty()) {
            pending.removeFirst().scheduleRebuild(false)
            ran++
        }
        if (pending.isNotEmpty()) {
            ReloadDebug.log("terrain pumped {} remaining {}", ran, pending.size)
        } else if (ran > 0) {
            logger.info("[RocketLoad] Chunk-Rebuild-Queue leer")
        }
    }

    @JvmStatic
    fun remaining(): Int = pending.size
}
