package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.fuel.RocketFuelFile
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.ShaderLoader
import net.minecraft.client.resource.DryFoliageColormapResourceSupplier
import net.minecraft.client.resource.FoliageColormapResourceSupplier
import net.minecraft.client.resource.GrassColormapResourceSupplier
import net.minecraft.client.resource.SplashTextResourceSupplier
import net.minecraft.client.sound.SoundManager
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceReloader
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ReloadSkips {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    class Domain(
        val label: String,
        val relevant: (RocketFuelFile) -> Boolean,
        val extraState: () -> String = { "" },
    )

    private val SOUNDS = Domain("Sounds", ResourceFingerprints::touchesSounds)
    private val SPLASHES = Domain("Splash-Texte", ResourceFingerprints::touchesTexts)
    private val COLORMAPS = Domain("Colormaps", ResourceFingerprints::touchesColormaps)
    private val LANGUAGE = Domain(
        "Sprache",
        ResourceFingerprints::touchesLang,
        { MinecraftClient.getInstance()?.languageManager?.language ?: "" },
    )
    private val FONTS = Domain("Fonts", ResourceFingerprints::touchesFonts)
    private val SHADERS = Domain("Shader", ResourceFingerprints::touchesShaders)
    private val TEXTURES = Domain("Texturen", ResourceFingerprints::touchesTextures)
    private val ATLAS = Domain(
        "Atlanten",
        ResourceFingerprints::touchesAtlas,
        { MinecraftClient.getInstance()?.options?.mipmapLevels?.value?.toString() ?: "" },
    )
    private val MODELS = Domain(
        "Modelle",
        ResourceFingerprints::touchesModels,
        { MinecraftClient.getInstance()?.options?.mipmapLevels?.value?.toString() ?: "" },
    )

    private fun domainForSimpleListener(listener: Any): Domain? = when (listener) {
        is SoundManager -> SOUNDS
        is SplashTextResourceSupplier -> SPLASHES
        is GrassColormapResourceSupplier, is FoliageColormapResourceSupplier,
        is DryFoliageColormapResourceSupplier -> COLORMAPS
        is ShaderLoader -> SHADERS
        else -> null
    }

    private val lastFingerprints = ConcurrentHashMap<String, List<String>>()
    private val skipMemo = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var lastBlocksLayout: String? = null

    private var lastEnabledIds: List<String>? = null

    @Volatile
    private var packSetChanged = false

    @Volatile
    private var skippedModels = false

    @JvmStatic
    fun packSetChanged(): Boolean = packSetChanged

    @JvmStatic
    fun skippedModels(): Boolean = skippedModels

    @JvmStatic
    fun beginReload() {
        skipMemo.clear()
        SpritePixels.beginReload()
        ReloadDebug.beginReload()
        skippedModels = false
        val ids = MinecraftClient.getInstance()?.resourcePackManager?.enabledIds?.toList()
        packSetChanged = lastEnabledIds != null && lastEnabledIds != ids
        lastEnabledIds = ids
        if (packSetChanged) {
            logger.info("[RocketLoad] Aktivierte Packs haben sich geaendert - voller Visual-Reload")
        }
    }

    @JvmStatic
    fun capture(manager: ResourceManager) {
        remember("LanguageManager", LANGUAGE, manager)
        remember("FontManager", FONTS, manager)
        remember("TextureManager", TEXTURES, manager)
        remember("Atlas", ATLAS, manager)
        remember("Models", MODELS, manager)
        remember("Visuals", ATLAS, manager)
        remember(SoundManager::class.java.name, SOUNDS, manager)
        remember(SplashTextResourceSupplier::class.java.name, SPLASHES, manager)
        remember(GrassColormapResourceSupplier::class.java.name, COLORMAPS, manager)
        remember(FoliageColormapResourceSupplier::class.java.name, COLORMAPS, manager)
        remember(DryFoliageColormapResourceSupplier::class.java.name, COLORMAPS, manager)
        remember(ShaderLoader::class.java.name, SHADERS, manager)
    }

    fun rememberBlocksLayout(signature: String?) {
        if (signature != null) lastBlocksLayout = signature
    }

    private fun remember(key: String, domain: Domain, manager: ResourceManager) {
        try {
            lastFingerprints[key] = ResourceFingerprints.fingerprint(
                manager.streamResourcePacks().toList(),
                domain.relevant,
            ) + domain.extraState()
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun shouldSkipSimpleListener(listener: Any, manager: ResourceManager): Boolean {
        val domain = domainForSimpleListener(listener) ?: return false
        return shouldSkip(listener.javaClass.name, domain, manager)
    }

    @JvmStatic
    fun shouldSkipLanguageReload(manager: ResourceManager): Boolean =
        shouldSkip("LanguageManager", LANGUAGE, manager)

    @JvmStatic
    fun shouldSkipFontReload(manager: ResourceManager): Boolean =
        shouldSkip("FontManager", FONTS, manager)

    @JvmStatic
    fun shouldSkipTextureReload(manager: ResourceManager): Boolean =
        shouldSkip("TextureManager", TEXTURES, manager)

    @JvmStatic
    fun shouldSkipVisualReload(reloadKey: Any, manager: ResourceManager): Boolean =
        shouldSkipAtlasReload(manager)

    @JvmStatic
    fun shouldSkipAtlasReload(manager: ResourceManager): Boolean {
        if (packSetChanged) return false
        return shouldSkip("Atlas", ATLAS, manager)
    }

    @JvmStatic
    fun shouldSkipModelReload(store: ResourceReloader.Store, manager: ResourceManager): Boolean {
        if (packSetChanged) {
            skippedModels = false
            return false
        }
        if (!shouldSkip("Models", MODELS, manager)) {
            skippedModels = false
            return false
        }
        if (shouldSkipAtlasReload(manager)) {
            logger.info("[RocketLoad] Modelle unveraendert, Atlas unveraendert - Bake uebersprungen")
            skippedModels = true
            return true
        }
        val layoutStart = System.nanoTime()
        val layout = AtlasLayouts.blocksLayoutStitch(store)
        ReloadDebug.log("blocks UV-layout now={} last={} hashMs={}", layout, lastBlocksLayout, (System.nanoTime() - layoutStart) / 1_000_000)
        if (layout == null) {
            skippedModels = false
            return false
        }
        val same = lastBlocksLayout != null && lastBlocksLayout == layout
        lastBlocksLayout = layout
        if (same) {
            logger.info("[RocketLoad] Nur Pixel, Block-Atlas-UVs gleich - Model-Bake uebersprungen")
        }
        skippedModels = same
        return same
    }

    private fun shouldSkip(key: String, domain: Domain, manager: ResourceManager): Boolean {
        if (packSetChanged) return false
        if (!RocketLoadConfig.instance.selectiveReload) return false
        skipMemo[key]?.let { return it }
        return try {
            val fingerprint = ResourceFingerprints.fingerprint(manager.streamResourcePacks().toList(), domain.relevant) +
                domain.extraState()
            val last = lastFingerprints.put(key, fingerprint)
            val skip = last != null && last == fingerprint
            ReloadDebug.skip(domain.label, skip, last, fingerprint)
            if (skip) {
                logger.info("[RocketLoad] {} unveraendert - Reload uebersprungen", domain.label)
            }
            skipMemo[key] = skip
            skip
        } catch (e: Exception) {
            logger.warn("[RocketLoad] Skip-Pruefung fuer {} fehlgeschlagen, Listener laeuft normal: {}", key, e.toString())
            skipMemo[key] = false
            false
        }
    }
}
