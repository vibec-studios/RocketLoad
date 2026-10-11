package dev.henny.rocketload.client

import dev.henny.rocketload.mixin.TextureAtlasAccessor
import net.minecraft.client.Minecraft
import net.minecraft.data.AtlasIds
import java.security.MessageDigest

object AtlasLayouts {

    fun blocksLayout(mc: Minecraft): String? {
        return try {
            val atlas = mc.atlasManager.getAtlasOrThrow(AtlasIds.BLOCKS)
            val accessor = atlas as TextureAtlasAccessor
            val sprites = accessor.rocketload_texturesByName()
            val digest = MessageDigest.getInstance("MD5")
            digest.update("${accessor.rocketload_width()}x${accessor.rocketload_height()}:${sprites.size};".toByteArray())
            for (id in sprites.keys.sortedBy { it.toString() }) {
                val sprite = sprites[id] ?: continue
                val contents = sprite.contents()
                digest.update("${contents.name()}:${sprite.x}:${sprite.y}:${contents.width()}:${contents.height()};".toByteArray())
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
