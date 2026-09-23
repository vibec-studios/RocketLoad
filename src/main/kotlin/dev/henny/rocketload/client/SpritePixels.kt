package dev.henny.rocketload.client

import net.minecraft.client.texture.NativeImage
import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

object SpritePixels {
    private val last = ConcurrentHashMap<Identifier, Long>()
    private val changedThisUpload = ConcurrentHashMap.newKeySet<Identifier>()

    @JvmStatic
    fun beginReload() {
        changedThisUpload.clear()
    }

    @JvmStatic
    fun shouldUpload(id: Identifier, image: NativeImage?, mipLevel: Int): Boolean {
        if (image == null) return true
        if (mipLevel > 0) return changedThisUpload.contains(id)
        val fingerprint = fingerprint(image)
        val changed = last.put(id, fingerprint) != fingerprint
        if (changed) changedThisUpload.add(id) else changedThisUpload.remove(id)
        return changed
    }

    private fun fingerprint(image: NativeImage): Long {
        val w = image.width
        val h = image.height
        var hash = w.toLong() * 31 + h
        if (w <= 0 || h <= 0) return hash
        val stepsX = (w / 4).coerceAtLeast(1)
        val stepsY = (h / 4).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                hash = hash * 31 + image.getColorArgb(x, y)
                x += stepsX
            }
            y += stepsY
        }
        return hash
    }
}
