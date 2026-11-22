package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import dev.henny.rocketload.api.impl.RocketLoadApiImpl
import dev.henny.rocketload.mixin.LoadingOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.LoadingOverlay
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.ReloadInstance
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

object BackgroundReloadDriver {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private val TOAST_ID = SystemToast.SystemToastId(5000L)
    private const val RAMP_START_MS = 1500L
    private const val RAMP_END_MS = 6000L
    private const val CONNECT_BUDGET_NANOS = 40_000_000L
    private const val MAX_BUDGET_NANOS = 40_000_000L
    private const val UNFOCUSED_BUDGET_NANOS = 250_000_000L

    private class ActiveReload(val reload: ReloadInstance, val startNanos: Long)

    @Volatile
    private var active: ActiveReload? = null

    private val applyQueue = ConcurrentLinkedQueue<Runnable>()
    private val finishQueue = ConcurrentLinkedQueue<Runnable>()
    private val applySeq = AtomicInteger()

    private val QUEUE_EXECUTOR = Executor { task ->
        val name = "q${applySeq.incrementAndGet()}"
        val enqueuedAt = System.nanoTime()
        applyQueue.add { ReloadDebug.runApply(name, enqueuedAt, task) }
    }

    @Volatile
    private var pumping = false

    @Volatile
    private var frameDeadline = 0L

    @JvmStatic
    fun frameDeadline(): Long = frameDeadline

    private fun isConnecting(mc: Minecraft): Boolean = mc.level == null && McCompat.screen(mc) is ConnectScreen

    private fun budgetNanos(mc: Minecraft?): Long {
        if (mc != null && !mc.isWindowActive) return UNFOCUSED_BUDGET_NANOS
        val configured = RocketLoadConfig.instance.applyBudgetMsPerTick.coerceIn(1, 50) * 1_000_000L
        val base = if (mc != null && isConnecting(mc)) maxOf(configured, CONNECT_BUDGET_NANOS) else configured
        val state = active ?: return base
        val elapsedMs = (System.nanoTime() - state.startNanos) / 1_000_000
        if (elapsedMs < RAMP_START_MS) return base
        if (elapsedMs > RAMP_END_MS) return MAX_BUDGET_NANOS
        val factor = Math.pow(2.0, (elapsedMs - RAMP_START_MS) / 1000.0)
        return (base * factor).toLong().coerceAtMost(MAX_BUDGET_NANOS)
    }

    private var deferredStart: CompletableFuture<Void>? = null
    private var followUp: CompletableFuture<Void>? = null
    private var framesUntilStart = 0

    @Volatile
    private var startingNow = false

    @Volatile
    private var pauseWorld = false

    @Volatile
    private var lastToastPercent = -1

    @JvmStatic
    fun hasStartedReload(): Boolean = active != null

    @JvmStatic
    fun isReloadActive(): Boolean = active != null || deferredStart != null

    @JvmStatic
    fun progress(): Float = active?.reload?.actualProgress ?: if (deferredStart != null) 0f else 1f

    @JvmStatic
    fun interceptReloadRequest(mc: Minecraft, isRecovery: Boolean): CompletableFuture<Void>? {
        if (isRecovery || startingNow || !RocketLoadConfig.instance.backgroundReload) {
            return null
        }
        deferredStart?.let { return it }
        if (active != null) {
            return followUp ?: CompletableFuture<Void>().also {
                followUp = it
                ReloadDebug.log("reload requested while active - follow-up queued")
            }
        }
        return deferStart(mc)
    }

    private fun deferStart(mc: Minecraft): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        deferredStart = future
        framesUntilStart = if (isConnecting(mc)) 0 else 1
        lastToastPercent = -1
        showToast(mc, Component.translatable("rocketload.toast.applying", 0))
        return future
    }

    @JvmStatic
    fun shouldPauseIntegratedServer(mc: Minecraft): Boolean {
        if (!pauseWorld || !RocketLoadConfig.instance.pauseIntegratedServerDuringReload) return false
        val server = mc.singleplayerServer ?: return false
        return !server.isPublished
    }

    @Volatile
    private var queuedReload = false

    @JvmStatic
    fun applyExecutor(mainThread: Executor, isRecovery: Boolean): Executor {
        val config = RocketLoadConfig.instance
        if (isRecovery || !config.backgroundReload || config.applyBudgetMsPerTick <= 0) {
            queuedReload = false
            return mainThread
        }
        queuedReload = true
        return QUEUE_EXECUTOR
    }

    @JvmStatic
    fun isQueuedApply(): Boolean = queuedReload

    @JvmStatic
    fun queueSize(): Int = applyQueue.size

    @JvmStatic
    fun enqueueApply(name: String, task: Runnable) {
        val enqueuedAt = System.nanoTime()
        applyQueue.add { ReloadDebug.runApply(name, enqueuedAt, task) }
    }

    @JvmStatic
    fun pumpApply() {
        val pending = deferredStart
        if (pending != null && active == null && !startingNow) {
            if (framesUntilStart > 0) {
                framesUntilStart--
            } else {
                startDeferred(pending)
            }
        }

        if (pumping) return
        if (applyQueue.isEmpty()) {
            drainFinish()
            return
        }

        val pumpStart = System.nanoTime()
        val deadline = pumpStart + budgetNanos(Minecraft.getInstance())
        pumping = true
        var ran = 0
        try {
            frameDeadline = deadline
            while (true) {
                val task = applyQueue.poll() ?: break
                try {
                    task.run()
                } catch (t: Throwable) {
                    logger.error("[RocketLoad] Apply-Task fehlgeschlagen", t)
                }
                ran++
                if (System.nanoTime() >= deadline) break
            }
        } finally {
            pumping = false
            ReloadDebug.pumpFrame(ran, (System.nanoTime() - pumpStart) / 1_000_000, applyQueue.size)
        }
        if (applyQueue.isEmpty() && System.nanoTime() < deadline) {
            drainFinish()
        }
    }

    private fun startDeferred(pending: CompletableFuture<Void>) {
        startingNow = true
        deferredStart = null
        try {
            Minecraft.getInstance().reloadResourcePacks().whenComplete { _, error ->
                if (error != null) pending.completeExceptionally(error) else pending.complete(null)
            }
        } catch (t: Throwable) {
            pending.completeExceptionally(t)
        } finally {
            startingNow = false
        }
    }

    @JvmStatic
    fun begin(mc: Minecraft, overlay: Overlay?): Boolean {
        if (!RocketLoadConfig.instance.backgroundReload) return false
        if (overlay !is LoadingOverlay) return false
        val accessor = overlay as LoadingOverlayAccessor
        val reload = accessor.rocketload_reload()
        val onFinish = accessor.rocketload_onFinish()

        val state = ActiveReload(reload, System.nanoTime())
        active = state
        pauseWorld = mc.singleplayerServer != null
        lastToastPercent = -1
        logger.info("[RocketLoad] Resource-Reload laeuft im Hintergrund, kein Splash-Screen")
        showToast(mc, Component.translatable("rocketload.toast.applying", 0))
        RocketLoadApiImpl.fireReloadStarted()

        reload.done().whenComplete { _, _ ->
            finishQueue.add { finish(mc, state, reload, onFinish) }
        }
        return true
    }

    private fun finish(mc: Minecraft, state: ActiveReload, reload: ReloadInstance, onFinish: java.util.function.Consumer<Optional<Throwable>>) {
        active = null
        pauseWorld = false
        queuedReload = false
        var failure: Throwable? = null
        try {
            reload.checkExceptions()
        } catch (t: Throwable) {
            failure = t
        }
        val ms = (System.nanoTime() - state.startNanos) / 1_000_000
        if (failure == null) {
            try {
                ChunkRebuildGuard.duringFinish { onFinish.accept(Optional.empty()) }
                McCompat.screen(mc)?.let { screen ->
                    val window = mc.window
                    screen.init(window.guiScaledWidth, window.guiScaledHeight)
                }
            } catch (t: Throwable) {
                logger.error("[RocketLoad] Reload-Abschluss fehlgeschlagen", t)
            }
            showToast(mc, Component.translatable("rocketload.toast.done", ms))
            logger.info("[RocketLoad] Hintergrund-Reload fertig nach {} ms", ms)
            ReloadDebug.summary(ms)
            RocketLoadApiImpl.fireReloadFinished(true, ms)
        } else {
            logger.error("[RocketLoad] Hintergrund-Reload fehlgeschlagen", failure)
            SystemToast.forceHide(McCompat.toastManager(mc), TOAST_ID)
            RocketLoadApiImpl.fireReloadFinished(false, ms)
            onFinish.accept(Optional.of(failure))
        }
        val next = followUp
        if (next != null) {
            followUp = null
            if (deferredStart == null) {
                val started = deferStart(mc)
                started.whenComplete { _, error -> if (error != null) next.completeExceptionally(error) else next.complete(null) }
            } else {
                deferredStart!!.whenComplete { _, error -> if (error != null) next.completeExceptionally(error) else next.complete(null) }
            }
        }
    }

    private fun drainFinish() {
        while (true) {
            val task = finishQueue.poll() ?: return
            try {
                task.run()
            } catch (t: Throwable) {
                logger.error("[RocketLoad] Reload-Abschluss fehlgeschlagen", t)
            }
        }
    }

    @JvmStatic
    fun tickToast(mc: Minecraft) {
        val state = active ?: return
        val percent = (state.reload.actualProgress * 100).toInt().coerceIn(0, 100)
        if (percent == lastToastPercent) return
        lastToastPercent = percent
        showToast(mc, Component.translatable("rocketload.toast.applying", percent))
    }

    private fun showToast(mc: Minecraft, message: Component) {
        if (!RocketLoadConfig.instance.toasts) return
        SystemToast.addOrUpdate(McCompat.toastManager(mc), TOAST_ID, Component.translatable("rocketload.toast.title"), message)
    }
}
