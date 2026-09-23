package dev.henny.rocketload.client

import dev.henny.rocketload.fuel.RocketFuelFile
import dev.henny.rocketload.pack.RocketFuelPackResources
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resource.DefaultResourcePack
import net.minecraft.resource.DirectoryResourcePack
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ZipResourcePack
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.WeakHashMap

object ResourceFingerprints {

    private val fuelByPack = Collections.synchronizedMap(WeakHashMap<ResourcePack, RocketFuelFile>())

    fun register(pack: ResourcePack, fuel: RocketFuelFile) {
        fuelByPack[pack] = fuel
    }

    fun describe(pack: ResourcePack): String {
        val fuel = (pack as? RocketFuelPackResources)?.fuel ?: fuelByPack[pack]
        if (fuel != null) {
            val d = fuel.domains
            return "fuel ${fuel.path.fileName} entries=${fuel.entries.size} tex=${d.textures} models=${d.models} atlases=${d.atlases} particles=${d.particles} geo=${d.geometry} fonts=${d.fonts} sounds=${d.sounds}"
        }
        return pack.javaClass.simpleName
    }


    fun fingerprint(packs: List<ResourcePack>, relevant: (RocketFuelFile) -> Boolean): List<String> =
        packs.mapNotNull { pack ->
            val fuel = (pack as? RocketFuelPackResources)?.fuel ?: fuelByPack[pack]
            when {
                fuel != null ->
                    if (relevant(fuel)) "fuel:${fuel.path.fileName}:${fuel.sourceSize}:${fuel.sourceMtime}" else null
                pack is DefaultResourcePack -> "vanilla:${pack.id}"
                pack is DirectoryResourcePack -> fileToken("dir", pack)
                pack is ZipResourcePack -> fileToken("zip", pack)
                else -> "static:${pack.javaClass.name}:${pack.id}"
            }
        }

    private fun fileToken(kind: String, pack: ResourcePack): String {
        val relative = pack.id.removePrefix("file/")
        val path = FabricLoader.getInstance().gameDir.resolve("resourcepacks").resolve(relative)
        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            "$kind:${pack.id}:${attrs.size()}:${attrs.lastModifiedTime().toMillis()}"
        } catch (_: Exception) {
            "$kind:${pack.id}"
        }
    }

    fun touchesGeometry(fuel: RocketFuelFile): Boolean = fuel.domains.geometry

    fun touchesSounds(fuel: RocketFuelFile): Boolean = fuel.domains.sounds

    fun touchesVisuals(fuel: RocketFuelFile): Boolean = fuel.domains.visuals

    fun touchesTextures(fuel: RocketFuelFile): Boolean = fuel.domains.textures

    fun touchesAtlas(fuel: RocketFuelFile): Boolean =
        fuel.domains.textures || fuel.domains.atlases || fuel.domains.particles

    fun touchesModels(fuel: RocketFuelFile): Boolean = fuel.domains.models

    fun touchesShaders(fuel: RocketFuelFile): Boolean = fuel.domains.shaders

    fun touchesTexts(fuel: RocketFuelFile): Boolean = fuel.domains.texts

    fun touchesColormaps(fuel: RocketFuelFile): Boolean = fuel.domains.colormaps

    fun touchesLang(fuel: RocketFuelFile): Boolean = fuel.domains.lang

    fun touchesFonts(fuel: RocketFuelFile): Boolean = fuel.domains.fonts
}
