package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.api.ReloadDomain
import dev.henny.rocketload.api.impl.RocketLoadApiImpl
import dev.henny.rocketload.fuel.RocketFuelFile
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderManager
import net.minecraft.client.resources.DryFoliageColorReloadListener
import net.minecraft.client.resources.FoliageColorReloadListener
import net.minecraft.client.resources.GrassColorReloadListener
import net.minecraft.client.resources.SplashManager
import net.minecraft.client.sounds.SoundManager
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.resources.ResourceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object ReloadSkips {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private class Domain(
        val id: ReloadDomain,
        val relevant: (RocketFuelFile.Domains) -> Boolean,
        val extraState: () -> String = { "" },
    ) {
        fun fingerprint(packs: List<ResourceFingerprints.PackFingerprint>): List<String> =
            ResourceFingerprints.fingerprint(packs, id == ReloadDomain.SOUNDS, relevant) + extraState()
    }

    private fun mipmaps(): String = Minecraft.getInstance()?.options?.mipmapLevels()?.get()?.toString() ?: ""

    private val SOUNDS = Domain(ReloadDomain.SOUNDS, { it.sounds })
    private val SPLASHES = Domain(ReloadDomain.SPLASH_TEXTS, { it.texts })
    private val COLORMAPS = Domain(ReloadDomain.COLORMAPS, { it.colormaps })
    private val LANGUAGE = Domain(ReloadDomain.LANGUAGE, { it.lang }, { Minecraft.getInstance()?.languageManager?.selected ?: "" })
    private val FONTS = Domain(ReloadDomain.FONTS, { it.fonts || it.textures })
    private val SHADERS = Domain(ReloadDomain.SHADERS, { it.shaders })
    private val TEXTURES = Domain(ReloadDomain.TEXTURES, { it.textures })
    private val VISUALS = Domain(ReloadDomain.VISUALS, { it.visuals }, ::mipmaps)
    private val GEOMETRY = Domain(ReloadDomain.GEOMETRY, { it.geometry })

    private val ALL = listOf(SOUNDS, SPLASHES, COLORMAPS, LANGUAGE, FONTS, SHADERS, TEXTURES, VISUALS, GEOMETRY)

    private fun domainForSimpleListener(listener: Any): Domain? = when (listener) {
        is SoundManager -> SOUNDS
        is SplashManager -> SPLASHES
        is GrassColorReloadListener, is FoliageColorReloadListener, is DryFoliageColorReloadListener -> COLORMAPS
        is ShaderManager -> SHADERS
        else -> null
    }

    private val lastFingerprints = ConcurrentHashMap<ReloadDomain, List<String>>()

    private val decisions = ConcurrentHashMap<ReloadDomain, Boolean>()

    @Volatile
    private var currentPacks: Lazy<List<ResourceFingerprints.PackFingerprint>>? = null

    private var lastSelectedIds: List<String>? = null

    @Volatile
    private var packSetChanged = false

    @JvmStatic
    fun packSetChanged(): Boolean = packSetChanged

    @JvmStatic
    fun decision(domain: ReloadDomain): Boolean? = decisions[domain]

    @JvmStatic
    fun beginReload(packs: List<PackResources>) {
        currentPacks = lazy { ResourceFingerprints.snapshot(packs) }
        decisions.clear()
        ReloadDebug.beginReload()
        val ids = Minecraft.getInstance()?.resourcePackRepository?.selectedIds?.toList()
        packSetChanged = lastSelectedIds != null && lastSelectedIds != ids
        lastSelectedIds = ids
        if (packSetChanged) {
            if (RocketLoadConfig.instance.selectivePackSetReload) {
                logger.info("[RocketLoad] Aktivierte Packs haben sich geaendert - nur betroffene Bereiche werden neu geladen")
            } else {
                logger.info("[RocketLoad] Aktivierte Packs haben sich geaendert - voller Reload")
            }
        }
    }

    @JvmStatic
    fun capture(manager: ResourceManager) {
        val packs = try {
            ResourceFingerprints.snapshot(manager.listPacks().toList())
        } catch (e: Exception) {
            logger.warn("[RocketLoad] Pack-Fingerprints konnten nicht erfasst werden: {}", e.toString())
            return
        }
        for (domain in ALL) {
            try {
                lastFingerprints[domain.id] = domain.fingerprint(packs)
            } catch (_: Exception) {
            }
        }
        if (lastSelectedIds == null) lastSelectedIds = Minecraft.getInstance()?.resourcePackRepository?.selectedIds?.toList()
    }

    @JvmStatic
    fun shouldSkipSimpleListener(listener: Any, manager: ResourceManager): Boolean {
        val domain = domainForSimpleListener(listener) ?: return false
        return shouldSkip(domain, manager)
    }

    @JvmStatic
    fun shouldSkipLanguage(manager: ResourceManager): Boolean = shouldSkip(LANGUAGE, manager)

    @JvmStatic
    fun shouldSkipFonts(manager: ResourceManager): Boolean = shouldSkip(FONTS, manager)

    @JvmStatic
    fun shouldSkipTextures(manager: ResourceManager): Boolean = shouldSkip(TEXTURES, manager)

    @JvmStatic
    fun shouldSkipShaders(manager: ResourceManager): Boolean = shouldSkip(SHADERS, manager)

    @JvmStatic
    fun shouldSkipVisuals(manager: ResourceManager): Boolean = shouldSkip(VISUALS, manager)

    @JvmStatic
    fun geometryUnchanged(manager: ResourceManager): Boolean {
        decisions[GEOMETRY.id]?.let { return it }
        val result = evaluate(GEOMETRY, manager)
        decisions[GEOMETRY.id] = result
        return result
    }

    private fun shouldSkip(domain: Domain, manager: ResourceManager): Boolean {
        decisions[domain.id]?.let { return it }
        synchronized(this) {
            decisions[domain.id]?.let { return it }
            val skip = RocketLoadConfig.instance.selectiveReload && evaluate(domain, manager) && !RocketLoadApiImpl.vetoSkip(domain.id)
            if (skip) logger.info("[RocketLoad] {} unveraendert - Reload uebersprungen", domain.id.label)
            decisions[domain.id] = skip
            RocketLoadApiImpl.fireSkipDecision(domain.id, skip)
            return skip
        }
    }

    private fun evaluate(domain: Domain, manager: ResourceManager): Boolean {
        return try {
            val packs = currentPacks?.value ?: ResourceFingerprints.snapshot(manager.listPacks().toList())
            val fingerprint = domain.fingerprint(packs)
            val last = lastFingerprints.put(domain.id, fingerprint)
            val same = last == fingerprint && (!packSetChanged || RocketLoadConfig.instance.selectivePackSetReload)
            ReloadDebug.skip(domain.id.label, same, last, fingerprint)
            same
        } catch (e: Exception) {
            logger.warn("[RocketLoad] Skip-Pruefung fuer {} fehlgeschlagen, Listener laeuft normal: {}", domain.id.label, e.toString())
            false
        }
    }
}
