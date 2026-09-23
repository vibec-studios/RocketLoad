package dev.henny.rocketload.client

import dev.henny.rocketload.mixin.SplashOverlayAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Overlay
import net.minecraft.client.gui.screen.SplashOverlay
import net.minecraft.client.texture.Sprite
import net.minecraft.client.toast.SystemToast
import net.minecraft.client.util.SpriteIdentifier
import net.minecraft.resource.ResourceReload
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

object BackgroundReloadDriver {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private val TOAST_ID = SystemToast.Type(5000L)

    private class ActiveReload(val reload: ResourceReload, val future: CompletableFuture<Void>, val startNanos: Long)

    @Volatile
    private var active: ActiveReload? = null

    private val applyQueue = ConcurrentLinkedQueue<Runnable>()

    private val applySeq = AtomicInteger()

    private val QUEUE_EXECUTOR = Executor { task ->
        val name = "q${applySeq.incrementAndGet()}"
        val enqueuedAt = System.nanoTime()
        applyQueue.add { ReloadDebug.runApply(name, enqueuedAt, task) }
    }

    private val prefilledSpriteMaps: MutableMap<Any, Map<SpriteIdentifier, Sprite>> =
        Collections.synchronizedMap(IdentityHashMap())

    @Volatile
    private var pumping = false

    private var deferredStart: CompletableFuture<Void>? = null
    private var framesUntilStart = 0

    @Volatile
    private var startingNow = false

    @Volatile
    private var pauseWorld = false

    @Volatile
    private var lastToastPercent = -1

    private val pendingFinish = java.util.concurrent.atomic.AtomicReference<Runnable?>(null)

    private var lastKeepAliveNs = 0L

    @Volatile
    private var atlasSpread = false

    @JvmStatic
    fun activeReloadFuture(): CompletableFuture<Void>? =
        if (startingNow) null else deferredStart ?: active?.future

    @JvmStatic
    fun shouldStartNow(): Boolean = startingNow

    @JvmStatic
    fun shouldPauseIntegratedServer(): Boolean {
        if (!dev.henny.rocketload.RocketLoadConfig.instance.pauseIntegratedServerDuringReload) return false
        return pauseWorld
    }

    @JvmStatic
    fun deferStart(client: MinecraftClient): CompletableFuture<Void> {
        deferredStart?.let { return it }
        val future = CompletableFuture<Void>()
        deferredStart = future
        framesUntilStart = 2
        lastToastPercent = -1
        logger.info("[RocketLoad] Reload um 2 Frames verschoben, damit Done nicht hakt")
        showToast(client, Text.translatable("rocketload.toast.applying", 0))
        return future
    }

    @JvmStatic
    fun applyExecutor(mainThread: Executor, force: Boolean): Executor {
        val config = dev.henny.rocketload.RocketLoadConfig.instance
        if (force || !config.backgroundReload || config.applyBudgetMsPerTick <= 0) return mainThread
        return QUEUE_EXECUTOR
    }

    @JvmStatic
    fun isQueuedApply(executor: Executor): Boolean = executor === QUEUE_EXECUTOR

    @JvmStatic
    fun isAtlasSpread(): Boolean = atlasSpread

    @JvmStatic
    fun beginAtlasSpread() {
        atlasSpread = true
    }

    @JvmStatic
    fun endAtlasSpread() {
        atlasSpread = false
    }

    @JvmStatic
    fun putPrefilledSpriteMap(stitch: Any, spriteMap: Map<SpriteIdentifier, Sprite>) {
        prefilledSpriteMaps[stitch] = spriteMap
    }

    @JvmStatic
    fun removePrefilledSpriteMap(stitch: Any) {
        prefilledSpriteMaps.remove(stitch)
    }

    @JvmStatic
    fun peekPrefilledSpriteMap(stitch: Any): Map<SpriteIdentifier, Sprite>? = prefilledSpriteMaps[stitch]

    @JvmStatic
    fun queueSize(): Int = applyQueue.size

    @JvmStatic
    fun enqueueApply(task: Runnable) {
        val enqueuedAt = System.nanoTime()
        applyQueue.add { ReloadDebug.runApply("chunk-rebuild", enqueuedAt, task) }
    }

    @JvmStatic
    fun pumpApply() {
        val pending = deferredStart
        if (pending != null && framesUntilStart > 0) {
            framesUntilStart--
            return
        }
        if (pending != null && framesUntilStart == 0 && active == null && !startingNow) {
            startingNow = true
            try {
                MinecraftClient.getInstance().reloadResources().whenComplete { _, error ->
                    if (error != null) pending.completeExceptionally(error) else pending.complete(null)
                    if (deferredStart === pending) deferredStart = null
                }
            } finally {
                startingNow = false
            }
        }

        if (pumping) return
        if (applyQueue.isEmpty()) {
            TerrainRebuildQueue.pump()
            drainPendingFinishIfIdle()
            return
        }

        pumping = true
        val pumpStart = System.nanoTime()
        var ran = 0
        try {
            val budgetMs = dev.henny.rocketload.RocketLoadConfig.instance.applyBudgetMsPerTick.coerceIn(1, 8)
            val deadline = System.nanoTime() + budgetMs * 1_000_000L
            while (true) {
                val task = applyQueue.poll() ?: break
                keepWindowAlive()
                val started = System.nanoTime()
                try {
                    task.run()
                } catch (t: Throwable) {
                    logger.error("[RocketLoad] Apply-Task fehlgeschlagen", t)
                }
                ran++
                keepWindowAlive()
                if (System.nanoTime() - started > 1_000_000L) break
                if (System.nanoTime() >= deadline) break
            }
        } finally {
            pumping = false
            ReloadDebug.pumpFrame(ran, (System.nanoTime() - pumpStart) / 1_000_000, applyQueue.size)
            TerrainRebuildQueue.pump()
        }
    }

    @JvmStatic
    fun begin(client: MinecraftClient, overlay: Overlay): Boolean {
        if (!dev.henny.rocketload.RocketLoadConfig.instance.backgroundReload) return false
        if (overlay !is SplashOverlay) return false
        val accessor = overlay as SplashOverlayAccessor
        val reload = accessor.rocketload_getReload()
        val onFinish = accessor.rocketload_getExceptionHandler()

        val state = ActiveReload(reload, CompletableFuture(), System.nanoTime())
        active = state
        pauseWorld = client.server != null
        lastToastPercent = -1
        logger.info("[RocketLoad] Resource-Reload laeuft im Hintergrund, kein Splash-Screen")
        ReloadDebug.log("begin wall-clock start, applyBudget={} ms/tick", dev.henny.rocketload.RocketLoadConfig.instance.applyBudgetMsPerTick)
        showToast(client, Text.translatable("rocketload.toast.applying", 0))

        val finishExecutor = Executor { task -> pendingFinish.set(task) }

        reload.whenComplete().whenCompleteAsync({ _, _ ->
            active = null
            pauseWorld = false
            try {
                reload.throwException()
                onFinish.accept(Optional.empty())
                val screen = client.currentScreen
                if (screen != null) {
                    val window = client.window
                    screen.resize(window.scaledWidth, window.scaledHeight)
                }
                val ms = (System.nanoTime() - state.startNanos) / 1_000_000
                showToast(client, Text.translatable("rocketload.toast.done", ms))
                logger.info("[RocketLoad] Hintergrund-Reload fertig nach {} ms", ms)
                ReloadDebug.summary(ms)
            } catch (t: Throwable) {
                logger.error("[RocketLoad] Hintergrund-Reload fehlgeschlagen", t)
                SystemToast.hide(client.toastManager, TOAST_ID)
                onFinish.accept(Optional.of(t))
            }
            state.future.complete(null)
        }, finishExecutor)
        return true
    }

    @JvmStatic
    fun keepWindowAlive() {
        val now = System.nanoTime()
        if (now - lastKeepAliveNs < 40_000_000L) return
        lastKeepAliveNs = now
        try {
            org.lwjgl.glfw.GLFW.glfwPollEvents()
        } catch (_: Throwable) {
        }
        val client = MinecraftClient.getInstance() ?: return
        tickToast(client)
    }

    private fun drainPendingFinishIfIdle() {
        if (pumping || applyQueue.isNotEmpty()) return
        val finish = pendingFinish.getAndSet(null) ?: return
        try {
            keepWindowAlive()
            finish.run()
        } catch (t: Throwable) {
            logger.error("[RocketLoad] Reload-Abschluss fehlgeschlagen", t)
        }
    }

    @JvmStatic
    fun tickToast(client: MinecraftClient) {
        val state = active ?: return
        val percent = (state.reload.progress * 100).toInt().coerceIn(0, 100)
        if (percent == lastToastPercent) return
        lastToastPercent = percent
        showToast(client, Text.translatable("rocketload.toast.applying", percent))
    }

    private fun showToast(client: MinecraftClient, message: Text) {
        if (!dev.henny.rocketload.RocketLoadConfig.instance.toasts) return
        SystemToast.show(
            client.toastManager,
            TOAST_ID,
            Text.translatable("rocketload.toast.title"),
            message,
        )
    }
}
