package dev.henny.rocketload.client

import dev.henny.rocketload.mixin.AtlasManagerStitchAccessor
import dev.henny.rocketload.mixin.SpriteAtlasTextureAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.AtlasManager
import net.minecraft.client.texture.Sprite
import net.minecraft.resource.ResourceReloader
import net.minecraft.util.Atlases
import net.minecraft.util.Identifier
import java.security.MessageDigest

object AtlasLayouts {

    fun blocksLayoutGpu(client: MinecraftClient): String? {
        return try {
            val atlas = client.atlasManager.getAtlasTexture(Atlases.BLOCKS)
            val accessor = atlas as SpriteAtlasTextureAccessor
            hash(Atlases.BLOCKS.toString(), accessor.rocketload_getWidth(), accessor.rocketload_getHeight(), accessor.rocketload_getSprites())
        } catch (_: Exception) {
            null
        }
    }

    fun blocksLayoutStitch(store: ResourceReloader.Store): String? {
        return try {
            val stitch = store.getOrThrow(AtlasManager.stitchKey)
            val future = try {
                stitch.getPreparations(Atlases.BLOCKS)
            } catch (_: Exception) {
                val preparations = (stitch as AtlasManagerStitchAccessor).rocketload_getPreparations()
                preparations[Atlases.BLOCKS] ?: preparations.values.firstOrNull()
            } ?: return null
            if (!future.isDone || future.isCompletedExceptionally) return null
            val result = future.getNow(null) ?: return null
            hash(Atlases.BLOCKS.toString(), result.width(), result.height(), result.sprites())
        } catch (_: Exception) {
            null
        }
    }

    private fun hash(atlasId: String, width: Int, height: Int, sprites: Map<Identifier, Sprite>): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update("$atlasId:${width}x${height}:${sprites.size};".toByteArray())
        for (id in sprites.keys.sortedBy { it.toString() }) {
            val sprite = sprites[id] ?: continue
            val contents = sprite.contents
            digest.update("${contents.id}:${sprite.x}:${sprite.y}:${contents.width}:${contents.height};".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
