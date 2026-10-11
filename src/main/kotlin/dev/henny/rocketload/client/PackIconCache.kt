package dev.henny.rocketload.client

import com.mojang.blaze3d.platform.NativeImage
import dev.henny.rocketload.pack.PackMetaCache
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.repository.Pack
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object PackIconCache {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private class Icon(val texture: Identifier, val stamp: Long) {
        @Volatile
        var checkedAt = System.nanoTime()
    }

    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val ready = ConcurrentHashMap<String, Icon>()
    private val threads = AtomicInteger()
    private val generation = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "RocketLoad-PackIcon-${threads.incrementAndGet()}").apply { isDaemon = true }
    }

    @JvmStatic
    fun get(pack: Pack, unknown: Identifier): Identifier {
        val id = pack.id
        val icon = ready[id]
        if (icon != null && !isStale(id, icon)) return icon.texture
        if (loading.add(id)) executor.execute { load(pack, id, unknown) }
        return icon?.texture ?: unknown
    }

    private fun isStale(id: String, icon: Icon): Boolean {
        if (icon.stamp == Long.MIN_VALUE) return false
        val now = System.nanoTime()
        if (now - icon.checkedAt < 1_000_000_000L) return false
        icon.checkedAt = now
        val current = PackMetaCache.pngForPackId(id)?.second ?: return false
        return current != icon.stamp
    }

    private fun load(pack: Pack, id: String, unknown: Identifier) {
        var image: NativeImage? = null
        var stamp = Long.MIN_VALUE
        try {
            val cached = PackMetaCache.pngForPackId(id)
            if (cached != null) {
                stamp = cached.second
                cached.first?.let { image = NativeImage.read(ByteArrayInputStream(it)) }
            } else {
                //? if >=26.3 {
                val resources = pack.openMetadata()
                //?} else {
                /*val resources = pack.open()
                *///?}
                resources.use { opened ->
                    opened.getRootResource("pack.png")?.get()?.use { stream -> image = NativeImage.read(stream) }
                }
            }
        } catch (e: Exception) {
            logger.debug("[RocketLoad] Pack-Icon {} nicht lesbar: {}", id, e.toString())
        }
        val native = image
        val mc = Minecraft.getInstance()
        if (native == null || mc == null) {
            ready[id] = Icon(unknown, stamp)
            loading.remove(id)
            return
        }
        mc.execute {
            try {
                val textureId = iconId(id)
                mc.textureManager.register(textureId, DynamicTexture({ "pack icon $id" }, native))
                val old = ready.put(id, Icon(textureId, stamp))
                if (old != null && old.texture != unknown && old.texture != textureId) mc.textureManager.release(old.texture)
            } catch (e: Exception) {
                native.close()
                ready[id] = Icon(unknown, stamp)
                logger.warn("[RocketLoad] Pack-Icon {} nicht registrierbar: {}", id, e.toString())
            } finally {
                loading.remove(id)
            }
        }
    }

    private fun iconId(packId: String): Identifier {
        val path = packId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9/._-]"), "_")
        return Identifier.fromNamespaceAndPath("rocketload", "icon/$path/${generation.incrementAndGet()}")
    }
}
