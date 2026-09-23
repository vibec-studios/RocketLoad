package dev.henny.rocketload.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.client.texture.TextureManager
import net.minecraft.resource.ResourcePackProfile
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import dev.henny.rocketload.pack.PackMetaCache

object PackIconCache {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val ready = ConcurrentHashMap<String, Identifier>()
    private val threads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "RocketLoad-PackIcon-${threads.incrementAndGet()}").apply { isDaemon = true }
    }

    @JvmStatic
    fun get(profile: ResourcePackProfile, unknown: Identifier, textureManager: TextureManager): Identifier {
        val id = profile.id
        ready[id]?.let { return it }
        if (loading.add(id)) {
            executor.execute { load(profile, id, unknown, textureManager) }
        }
        return unknown
    }

    private fun load(profile: ResourcePackProfile, id: String, unknown: Identifier, textureManager: TextureManager) {
        var image: NativeImage? = null
        try {
            val cached = PackMetaCache.png(id)
            if (cached != null) {
                image = NativeImage.read(java.io.ByteArrayInputStream(cached))
            } else {
                profile.createResourcePack().use { pack ->
                    val supplier = pack.openRoot("pack.png") ?: return@use
                    supplier.get().use { stream ->
                        image = NativeImage.read(stream)
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Icon {} nicht lesbar: {}", id, e.toString())
        }
        val native = image
        val client = MinecraftClient.getInstance()
        if (native == null || client == null) {
            ready[id] = unknown
            return
        }
        client.execute {
            try {
                val textureId = iconId(id)
                textureManager.registerTexture(
                    textureId,
                    NativeImageBackedTexture({ "pack icon $id" }, native),
                )
                ready[id] = textureId
            } catch (e: Exception) {
                native.close()
                ready[id] = unknown
                logger.warn("[RocketLoad] Pack-Icon {} nicht registrierbar: {}", id, e.toString())
            }
        }
    }

    private fun iconId(packId: String): Identifier {
        val path = packId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9/._-]"), "_")
        return Identifier.of("rocketload", "icon/$path")
    }
}
