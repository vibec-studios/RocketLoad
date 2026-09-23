package dev.henny.rocketload.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import dev.henny.rocketload.mixin.SpriteAtlasTextureAccessor
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.texture.Sprite
import net.minecraft.client.texture.SpriteAtlasTexture
import net.minecraft.client.texture.SpriteContents
import net.minecraft.util.math.MathHelper
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.OptionalInt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

object AtlasGpuUpload {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private val pending = AtomicReference<CompletableFuture<Void>?>(null)

    @JvmStatic
    fun start(atlas: SpriteAtlasTexture) {
        val future = CompletableFuture<Void>()
        pending.set(future)
        val job = Job(atlas)
        BackgroundReloadDriver.enqueueApply { pump(job, future) }
    }

    @JvmStatic
    fun consumeFuture(): CompletableFuture<Void> =
        pending.getAndSet(null) ?: CompletableFuture.completedFuture(null)

    private fun pump(job: Job, future: CompletableFuture<Void>) {
        try {
            val budget = System.nanoTime() + 4_000_000L
            var ran = 0
            while (job.index < job.sprites.size && System.nanoTime() < budget) {
                uploadOneSprite(job)
                job.index++
                ran++
            }
            ReloadDebug.log(
                "atlas GPU {} {}/{} thisFrame={}",
                job.atlasId,
                job.index,
                job.sprites.size,
                ran,
            )
            if (job.index < job.sprites.size) {
                BackgroundReloadDriver.enqueueApply { pump(job, future) }
                return
            }
            blitAndFinish(job)
            logger.info("[RocketLoad] Atlas {} GPU-Upload fertig ({} Sprites, ueber Frames)", job.atlasId, job.sprites.size)
            future.complete(null)
        } catch (t: Throwable) {
            job.closeScratch()
            future.completeExceptionally(t)
        }
    }

    private fun uploadOneSprite(job: Job) {
        val sprite = job.sprites[job.index]
        val contents = sprite.contents
        sprite.putSpriteInfo(
            job.info,
            job.index * job.perSprite,
            job.mipLevel,
            job.width,
            job.height,
            job.infoStride,
        )
        val device = RenderSystem.getDevice()
        val texture = device.createTexture(
            { contents.id.toString() },
            5,
            TextureFormat.RGBA8,
            contents.width,
            contents.height,
            1,
            job.numMipLevels,
        )
        val views = arrayOfNulls<GpuTextureView>(job.numMipLevels)
        for (mip in 0..job.mipLevel) {
            sprite.upload(texture, mip)
            views[mip] = device.createTextureView(texture)
        }
        @Suppress("UNCHECKED_CAST")
        job.temps.add(views as Array<GpuTextureView>)
    }

    private fun blitAndFinish(job: Job) {
        val device = RenderSystem.getDevice()
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST, true)
        val infoBuffer = device.createBuffer({ "SpriteInfo ${job.atlasId}" }, 128, job.info)
        try {
            val accessor = job.atlas as SpriteAtlasTextureAccessor
            for (mip in 0 until job.numMipLevels) {
                device.createCommandEncoder().createRenderPass(
                    { "atlas blit ${job.atlasId} mip$mip" },
                    accessor.rocketload_getMipTextures()[mip],
                    OptionalInt.empty(),
                ).use { pass ->
                    pass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT)
                    for (i in job.sprites.indices) {
                        pass.bindTexture("Sprite", job.temps[i][mip], sampler)
                        pass.setUniform(
                            "SpriteAnimationInfo",
                            infoBuffer.slice(
                                (i * job.perSprite + mip * job.infoStride).toLong(),
                                SpriteContents.SPRITE_INFO_SIZE.toLong(),
                            ),
                        )
                        pass.draw(0, 6)
                    }
                }
            }
        } finally {
            infoBuffer.close()
            job.closeScratch()
        }
        (job.atlas as SpriteAtlasTextureAccessor).rocketload_uploadAnimations()
    }

    private class Job(val atlas: SpriteAtlasTexture) {
        val atlasId = atlas.id
        val accessor = atlas as SpriteAtlasTextureAccessor
        val width = accessor.rocketload_getWidth()
        val height = accessor.rocketload_getHeight()
        val mipLevel = accessor.rocketload_getMipLevel()
        val numMipLevels = accessor.rocketload_getNumMipLevels()
        val infoStride = MathHelper.roundUpToMultiple(
            SpriteContents.SPRITE_INFO_SIZE,
            RenderSystem.getDevice().uniformOffsetAlignment,
        )
        val perSprite = infoStride * numMipLevels
        val sprites: List<Sprite> = accessor.rocketload_getSpritesToLoad().filter { !it.contents.isAnimated }
        val info: ByteBuffer = MemoryUtil.memAlloc(sprites.size * perSprite)
        val temps = ArrayList<Array<GpuTextureView>>(sprites.size)
        var index = 0

        fun closeScratch() {
            for (views in temps) {
                for (view in views) {
                    val texture = view.texture()
                    view.close()
                    texture.close()
                }
            }
            temps.clear()
            MemoryUtil.memFree(info)
        }
    }
}
