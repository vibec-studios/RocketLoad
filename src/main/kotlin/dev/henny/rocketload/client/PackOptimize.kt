package dev.henny.rocketload.client

import dev.henny.rocketload.fuel.RocketFuelCache
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path

object PackOptimize {
    private val logger = LoggerFactory.getLogger("RocketLoad")
    private val TOAST_ID = SystemToast.SystemToastId(4000L)

    @Volatile
    private var running = false

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun start(packsDir: Path) {
        if (running) return
        running = true
        toast(Component.translatable("rocketload.pack.optimizing"))
        val mc = Minecraft.getInstance()
        RocketFuelCache.optimizeAll(packsDir, { done, total ->
            mc?.execute { toast(Component.translatable("rocketload.toast.optimize_progress", done, total)) }
        }, { converted, ms ->
            running = false
            logger.info("[RocketLoad] Optimiert: {} Pack(s) in {} ms", converted, ms)
            mc?.execute { toast(Component.translatable("rocketload.toast.optimized", converted)) }
        })
    }

    @JvmStatic
    fun toast(body: Component) {
        val mc = Minecraft.getInstance() ?: return
        SystemToast.addOrUpdate(McCompat.toastManager(mc), TOAST_ID, Component.translatable("rocketload.toast.title"), body)
    }
}
