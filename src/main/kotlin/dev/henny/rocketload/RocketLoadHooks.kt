package dev.henny.rocketload

import dev.henny.rocketload.client.ChunkRebuildGuard
import dev.henny.rocketload.fuel.RocketFuelCache
import dev.henny.rocketload.pack.RocketFuelPackResources
import net.minecraft.resource.OverlayResourcePack
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourcePackInfo
import net.minecraft.resource.ResourcePackProfile
import java.io.File

object RocketLoadHooks {

    private val fullOpen = ThreadLocal.withInitial { false }

    @JvmStatic
    fun beginFullOpen() {
        fullOpen.set(true)
    }

    @JvmStatic
    fun endFullOpen() {
        fullOpen.set(false)
    }

    @JvmStatic
    fun isFullOpen(): Boolean = fullOpen.get()

    @JvmStatic
    fun openPrimary(content: File, info: ResourcePackInfo): ResourcePack? {
        val fuel = RocketFuelCache.get(content.toPath()) ?: return null
        val pack = RocketFuelPackResources(info, fuel)
        ChunkRebuildGuard.register(pack, fuel)
        return pack
    }

    @JvmStatic
    fun openFull(content: File, info: ResourcePackInfo, metadata: ResourcePackProfile.Metadata): ResourcePack? {
        val source = content.toPath()
        val fuel = RocketFuelCache.get(source)
        if (fuel == null) {
            RocketFuelCache.scheduleConvert(source)
            return null
        }
        val primary = RocketFuelPackResources(info, fuel)
        ChunkRebuildGuard.register(primary, fuel)
        val overlays = metadata.overlays()
        val result: ResourcePack = if (overlays.isEmpty()) {
            primary
        } else {
            OverlayResourcePack(
                primary,
                overlays.map { overlay ->
                    RocketFuelPackResources(info, fuel, overlay, releaseOnClose = false)
                        .also { ChunkRebuildGuard.register(it, fuel) }
                },
            )
        }
        ChunkRebuildGuard.register(result, fuel)
        return result
    }
}
