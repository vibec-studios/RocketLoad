package dev.henny.rocketload.client

import dev.henny.rocketload.RocketLoadConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.packs.PackResources
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ReloadDebug {
    private val logger = LoggerFactory.getLogger("RocketLoad")

    private val applyCount = AtomicInteger()
    private val applyNanos = AtomicLong()
    private val pumpFrames = AtomicInteger()
    private val skipCount = AtomicInteger()
    private val reloadCount = AtomicInteger()
    private val samples = ConcurrentLinkedQueue<Sample>()

    private data class Sample(val name: String, val runMs: Long, val queuedMs: Long)

    private val enabled: Boolean by lazy {
        java.lang.Boolean.getBoolean("rocketload.debug") ||
            ReloadDebug::class.java.getResource("/rocketload-dev.flag") != null ||
            runCatching { FabricLoader.getInstance().isDevelopmentEnvironment || RocketLoadConfig.instance.debugTimings }.getOrDefault(false)
    }

    @JvmStatic
    fun enabled(): Boolean = enabled

    @JvmStatic
    fun log(message: String, vararg args: Any?) {
        if (enabled) logger.info("[RocketLoad][dbg] $message", *args)
    }

    @JvmStatic
    fun beginReload() {
        if (!enabled) return
        applyCount.set(0)
        applyNanos.set(0)
        pumpFrames.set(0)
        skipCount.set(0)
        reloadCount.set(0)
        samples.clear()
        log("Reload-Debug aktiv")
    }

    @JvmStatic
    fun packs(packs: List<PackResources>) {
        if (!enabled) return
        log("createReload: {} Pack(s)", packs.size)
        for (pack in packs) log("  pack {} -> {}", pack.packId(), ResourceFingerprints.describe(pack))
    }

    @JvmStatic
    fun skip(domain: String, skipped: Boolean, last: List<String>?, current: List<String>) {
        if (!enabled) return
        if (skipped) {
            skipCount.incrementAndGet()
            log("SKIP {} fingerprint={}", domain, current)
        } else {
            reloadCount.incrementAndGet()
            log("RELOAD {} last={} now={}", domain, last, current)
        }
    }

    @JvmStatic
    fun runApply(name: String, enqueuedAtNs: Long, task: Runnable) {
        if (!enabled) {
            task.run()
            return
        }
        val start = System.nanoTime()
        try {
            task.run()
        } finally {
            val nanos = System.nanoTime() - start
            val runMs = nanos / 1_000_000
            val queuedMs = (start - enqueuedAtNs) / 1_000_000
            applyCount.incrementAndGet()
            applyNanos.addAndGet(nanos)
            if (runMs >= 1 || queuedMs >= 50) {
                samples.add(Sample(name, runMs, queuedMs))
                while (samples.size > 64) samples.poll()
                log("apply {} ran {} ms, queued {} ms, queueLeft={}", name, runMs, queuedMs, BackgroundReloadDriver.queueSize())
            }
        }
    }

    @JvmStatic
    fun pumpFrame(ran: Int, spentMs: Long, queueLeft: Int) {
        if (!enabled || ran <= 0) return
        pumpFrames.incrementAndGet()
        if (spentMs >= 1 || queueLeft > 0) {
            log("pump frame#{} ran {} task(s) in {} ms, queueLeft={}", pumpFrames.get(), ran, spentMs, queueLeft)
        }
    }

    @JvmStatic
    fun summary(wallMs: Long) {
        if (!enabled) return
        val top = samples.sortedWith(compareByDescending<Sample> { it.runMs }.thenByDescending { it.queuedMs }).take(15)
        log(
            "summary wall={} ms, applySum={} ms, applyTasks={}, pumpFrames={}, skip={}, reload={}",
            wallMs, applyNanos.get() / 1_000_000, applyCount.get(), pumpFrames.get(), skipCount.get(), reloadCount.get(),
        )
        for (sample in top) log("  slow {} run={} ms queued={} ms", sample.name, sample.runMs, sample.queuedMs)
    }
}
