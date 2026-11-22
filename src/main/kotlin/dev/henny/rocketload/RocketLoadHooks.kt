package dev.henny.rocketload

import dev.henny.rocketload.client.ResourceFingerprints
import dev.henny.rocketload.fuel.RocketFuelCache
import dev.henny.rocketload.pack.PackListResources
import dev.henny.rocketload.pack.PackMetaCache
import dev.henny.rocketload.pack.RocketFuelPackResources
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.repository.Pack
import java.io.File
//? if >=26.3 {
import net.minecraft.server.packs.OverlayedPackResources
//?} else {
/*import net.minecraft.server.packs.CompositePackResources
*///?}

object RocketLoadHooks {

    private val fullOpen = ThreadLocal.withInitial { 0 }

    @JvmStatic
    fun beginFullOpen() {
        fullOpen.set(fullOpen.get() + 1)
    }

    @JvmStatic
    fun endFullOpen() {
        fullOpen.set((fullOpen.get() - 1).coerceAtLeast(0))
    }

    @JvmStatic
    fun isFullOpen(): Boolean = fullOpen.get() > 0

    @JvmStatic
    fun trackZip(pack: Any, file: File) {
        if (ResourceFingerprints.fuelOf(pack) == null) ResourceFingerprints.registerZip(pack, file)
    }

    @JvmStatic
    fun openMetadata(content: File, info: PackLocationInfo): PackListResources? {
        val entry = PackMetaCache.openMetadataPack(content) ?: return null
        return PackListResources(info, entry.mcmeta, entry.png)
    }

    @JvmStatic
    fun openFull(content: File, info: PackLocationInfo, metadata: Pack.Metadata): PackResources? {
        val source = content.toPath()
        val fuel = RocketFuelCache.acquire(source)
        if (fuel == null) {
            RocketFuelCache.scheduleConvert(source)
            return null
        }
        val primary = RocketFuelPackResources(info, fuel)
        val overlays = metadata.overlays()
        if (overlays.isEmpty()) {
            ResourceFingerprints.registerDownload(primary, source)
            return primary
        }
        val overlayPacks: List<PackResources> = overlays.map { overlay ->
            RocketFuelPackResources(info, fuel, overlay, releaseOnClose = false)
        }
        //? if >=26.3 {
        val result = OverlayedPackResources(primary, overlayPacks)
        //?} else {
        /*val result = CompositePackResources(primary, overlayPacks)
        *///?}
        ResourceFingerprints.register(result, fuel)
        ResourceFingerprints.registerDownload(result, source)
        return result
    }
}
