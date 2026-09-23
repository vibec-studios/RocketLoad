package dev.henny.rocketload.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.toast.SystemToast
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.nio.file.Path

object PackOptimize {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    @Volatile
    var running = false
        private set

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun start(packsDir: Path) {
        if (running) return
        running = true
        toast(Text.translatable("rocketload.pack.optimizing"))
        val client = MinecraftClient.getInstance()
        dev.henny.rocketload.fuel.RocketFuelCache.optimizeAll(packsDir, { done, total ->
            client?.execute {
                toast(Text.translatable("rocketload.toast.optimize_progress", done, total))
            }
        }, { converted, ms ->
            running = false
            logger.info("[RocketLoad] Optimiert: {} Pack(s) in {} ms", converted, ms)
            client?.execute {
                toast(Text.translatable("rocketload.toast.optimized", converted))
            }
        })
    }

    private fun toast(body: Text) {
        val client = MinecraftClient.getInstance() ?: return
        SystemToast.show(
            client.toastManager,
            SystemToast.Type(4000L),
            Text.translatable("rocketload.toast.title"),
            body,
        )
    }
}
