package dev.henny.rocketload.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.toast.SystemToast
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

class RocketloadClient : ClientModInitializer {

    override fun onInitializeClient() {
        val logger = LoggerFactory.getLogger("RocketLoad")
        logger.info("[RocketLoad] Bereit - Resourcepacks werden als .rocketfuel gecacht")
        if (ReloadDebug.enabled()) {
            logger.info("[RocketLoad] Debug-Timings an (dev-jar, debugTimings oder -Drocketload.debug=true)")
        }

        val config = dev.henny.rocketload.RocketLoadConfig.instance
        dev.henny.rocketload.fuel.RocketFuelCache.housekeeping(config.cacheRetentionDays)

        val packDir = FabricLoader.getInstance().gameDir.resolve("resourcepacks")
        dev.henny.rocketload.pack.PackMetaCache.prefetchAsync(packDir)
        if (config.warmupConversion) dev.henny.rocketload.fuel.RocketFuelCache.preconvertAll(packDir) { converted, ms ->
            if (converted > 0) {
                logger.info("[RocketLoad] Warmup: {} Pack(s) in {} ms optimiert", converted, ms)
                if (!config.toasts) return@preconvertAll
                val client = MinecraftClient.getInstance() ?: return@preconvertAll
                client.execute {
                    SystemToast.show(
                        client.toastManager,
                        SystemToast.Type(5000L),
                        Text.translatable("rocketload.toast.title"),
                        Text.translatable("rocketload.toast.optimized", converted),
                    )
                }
            }
        }

        var baselineCaptured = false
        var testTicks = 0
        var testReloadTriggered = false
        val testMode = System.getenv("ROCKETLOAD_AUTO_RELOAD") == "1"

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!baselineCaptured && client.overlay == null) {
                baselineCaptured = true
                ChunkRebuildGuard.captureBaseline(client)
            }
            BackgroundReloadDriver.tickToast(client)

            if (testMode) {
                testTicks++
                if (!testReloadTriggered && testTicks >= 200 && client.currentScreen != null) {
                    testReloadTriggered = true
                    logger.info("[RocketLoad] Testmodus: loese Reload 1 aus (Tick {})", testTicks)
                    val start = System.nanoTime()
                    client.reloadResources().thenRun {
                        val ms = (System.nanoTime() - start) / 1_000_000
                        logger.info("[RocketLoad] Testmodus: Reload 1 fertig nach {} ms", ms)
                        client.execute {
                            try {
                                val testPack = packDir.resolve("rockettest.zip")
                                if (java.nio.file.Files.exists(testPack)) {
                                    java.nio.file.Files.setLastModifiedTime(
                                        testPack,
                                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                                    )
                                }
                            } catch (e: Exception) {
                                logger.warn("[RocketLoad] Testmodus: Pack nicht anfassbar: {}", e.toString())
                            }
                            client.resourcePackManager.scanPacks()
                            val added = client.resourcePackManager.enable("file/rockettest.zip")
                            val start2 = System.nanoTime()
                            logger.info("[RocketLoad] Testmodus: loese Reload 2 aus (voller Reload, Pack aktiviert: {})", added)
                            client.reloadResources().thenRun {
                                val ms2 = (System.nanoTime() - start2) / 1_000_000
                                logger.info("[RocketLoad] Testmodus: Reload 2 fertig nach {} ms", ms2)
                                client.execute { client.scheduleStop() }
                            }
                        }
                    }
                }
            }
        }
    }
}
