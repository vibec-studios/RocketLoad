package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.fuel.RocketFuelCache
import dev.henny.rocketload.pack.PackMetaCache
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class RocketloadClient : ClientModInitializer {

    private var followUpTest: (() -> Unit)? = null

    override fun onInitializeClient() {
        val logger = LoggerFactory.getLogger("RocketLoad")
        logger.info("[RocketLoad] Bereit - Resourcepacks werden als .rocketfuel gecacht")
        if (ReloadDebug.enabled()) logger.info("[RocketLoad] Debug-Timings an")

        val config = RocketLoadConfig.instance
        RocketFuelCache.housekeeping(config.cacheRetentionDays)
        PackMetaCache.housekeeping(config.cacheRetentionDays)

        val packDir = FabricLoader.getInstance().gameDir.resolve("resourcepacks")
        PackMetaCache.prefetchAsync(packDir)
        if (config.warmupConversion) {
            RocketFuelCache.optimizeAll(packDir, null) { converted, ms ->
                if (converted <= 0) return@optimizeAll
                logger.info("[RocketLoad] Warmup: {} Pack(s) in {} ms optimiert", converted, ms)
                if (config.toasts) {
                    Minecraft.getInstance()?.execute { PackOptimize.toast(Component.translatable("rocketload.toast.optimized", converted)) }
                }
            }
        }

        var baselineCaptured = false
        var testTicks = 0
        var testReloadTriggered = false
        val testMode = System.getenv("ROCKETLOAD_AUTO_RELOAD") == "1"

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!baselineCaptured && McCompat.overlay(mc) == null) {
                baselineCaptured = true
                ChunkRebuildGuard.captureBaseline(mc)
            }
            BackgroundReloadDriver.tickToast(mc)

            if (testMode) {
                testTicks++
                if (!testReloadTriggered && testTicks >= 200 && McCompat.screen(mc) != null) {
                    testReloadTriggered = true
                    runTestSequence(mc, packDir)
                }
                val pending = followUpTest
                if (pending != null && BackgroundReloadDriver.hasStartedReload()) {
                    followUpTest = null
                    pending()
                }
            }
        }
    }

    private fun testPackName(): String = System.getenv("ROCKETLOAD_TEST_PACK") ?: "rockettest.zip"

    private fun runTestSequence(mc: Minecraft, packDir: java.nio.file.Path) {
        val logger = LoggerFactory.getLogger("RocketLoad")
        logger.info("[RocketLoad] Testmodus: Reload 1 (unveraendert, Skip-Pfad)")
        val start = System.nanoTime()
        mc.reloadResourcePacks().thenRun {
            logger.info("[RocketLoad] Testmodus: Reload 1 fertig nach {} ms", (System.nanoTime() - start) / 1_000_000)
            mc.execute {
                try {
                    val testPack = packDir.resolve(testPackName())
                    if (Files.exists(testPack)) Files.setLastModifiedTime(testPack, FileTime.fromMillis(System.currentTimeMillis()))
                } catch (e: Exception) {
                    logger.warn("[RocketLoad] Testmodus: Pack nicht anfassbar: {}", e.toString())
                }
                mc.resourcePackRepository.reload()
                val added = mc.resourcePackRepository.addPack("file/${testPackName()}")
                val start2 = System.nanoTime()
                logger.info("[RocketLoad] Testmodus: Reload 2 + 3 gleichzeitig (Folge-Reload-Test, Pack aktiviert: {})", added)
                val second = mc.reloadResourcePacks()
                followUpTest = {
                    logger.info("[RocketLoad] Testmodus: Reload 3 waehrend Reload 2 angefordert")
                    val third = mc.reloadResourcePacks()
                    second.thenCombine(third) { _, _ -> null }.thenRun {
                        logger.info("[RocketLoad] Testmodus: Reload 2+3 fertig nach {} ms", (System.nanoTime() - start2) / 1_000_000)
                        mc.execute { mc.stop() }
                    }
                }
            }
        }
    }
}
