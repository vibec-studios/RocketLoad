package dev.henny.rocketload.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Debug only: per-listener timeline of a reload (when preparation ended, when the shared barrier let it apply,
 * how much main-thread time its apply took). Written to the log with the reload summary.
 */
public final class ReloadTimeline {
    private ReloadTimeline() {
    }

    private static final class Entry {
        final String name;
        final int index;
        final AtomicLong prepNanos = new AtomicLong();
        final AtomicLong prepEnd = new AtomicLong();
        volatile long waitAt;
        volatile long gateAt;
        final AtomicLong applyNanos = new AtomicLong();
        final AtomicLong applyMax = new AtomicLong();
        final AtomicLong applyEnd = new AtomicLong();
        final AtomicInteger applyTasks = new AtomicInteger();

        Entry(String name, int index) {
            this.name = name;
            this.index = index;
        }
    }

    private static volatile Object owner;
    private static volatile long start;
    private static final Map<PreparableReloadListener, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();

    private static Entry entry(Object instance, PreparableReloadListener listener) {
        if (owner != instance) {
            synchronized (ReloadTimeline.class) {
                if (owner != instance) {
                    ENTRIES.clear();
                    NEXT.set(0);
                    start = System.nanoTime();
                    owner = instance;
                }
            }
        }
        return ENTRIES.computeIfAbsent(listener, l -> new Entry(l.getName(), NEXT.getAndIncrement()));
    }

    public static PreparableReloadListener.PreparationBarrier barrier(Object instance, PreparableReloadListener listener,
                                                                       PreparableReloadListener.PreparationBarrier barrier) {
        if (!ReloadDebug.enabled()) return barrier;
        Entry e = entry(instance, listener);
        return new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <T> CompletableFuture<T> wait(T t) {
                e.waitAt = System.nanoTime();
                return barrier.wait(t).whenComplete((v, err) -> e.gateAt = System.nanoTime());
            }
        };
    }

    public static Executor tasks(Object instance, PreparableReloadListener listener, Executor executor) {
        if (!ReloadDebug.enabled()) return executor;
        Entry e = entry(instance, listener);
        return r -> executor.execute(() -> {
            long t0 = System.nanoTime();
            try {
                r.run();
            } finally {
                long t1 = System.nanoTime();
                e.prepNanos.addAndGet(t1 - t0);
                e.prepEnd.accumulateAndGet(t1, Math::max);
            }
        });
    }

    public static Executor apply(Object instance, PreparableReloadListener listener, Executor executor) {
        if (!ReloadDebug.enabled()) return executor;
        Entry e = entry(instance, listener);
        return r -> executor.execute(() -> {
            long t0 = System.nanoTime();
            try {
                r.run();
            } finally {
                long t1 = System.nanoTime();
                e.applyNanos.addAndGet(t1 - t0);
                e.applyMax.accumulateAndGet(t1 - t0, Math::max);
                e.applyEnd.accumulateAndGet(t1, Math::max);
                e.applyTasks.incrementAndGet();
            }
        });
    }

    private static long ms(long at) {
        return at == 0 ? -1 : (at - start) / 1_000_000;
    }

    static void log() {
        if (!ReloadDebug.enabled() || ENTRIES.isEmpty()) return;
        List<Entry> list = new ArrayList<>(ENTRIES.values());
        list.sort((a, b) -> Integer.compare(a.index, b.index));
        ReloadDebug.log("timeline (ms ab Start): name | prepCPU | prepFertig | wartet | Freigabe | applyCPU | applyMax | applyFertig | tasks");
        for (Entry e : list) {
            ReloadDebug.log("TL {} | {} | {} | {} | {} | {} | {} | {} | {}", e.name, e.prepNanos.get() / 1_000_000, ms(e.prepEnd.get()),
                    ms(e.waitAt), ms(e.gateAt), e.applyNanos.get() / 1_000_000, e.applyMax.get() / 1_000_000, ms(e.applyEnd.get()),
                    e.applyTasks.get());
        }
    }
}
