package dev.henny.rocketload.client;

import dev.henny.rocketload.mixin.PendingStitchAccessor;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class AtlasSpread {
    private static final Map<Object, Map<?, ?>> PREFILLED = Collections.synchronizedMap(new IdentityHashMap<>());

    private AtlasSpread() {
    }

    public interface PendingStitches {
        List<?> rocketload_pendingStitches();
    }

    public static Map<?, ?> takePrefilled(Object results) {
        return PREFILLED.get(results);
    }

    public static CompletableFuture<Void> spread(PendingStitches results, CompletableFuture<?> prepared, Consumer<Object> apply, Executor executor) {
        return prepared.thenComposeAsync(ignored -> {
            Map<Object, Object> spriteMap = new HashMap<>();
            CompletableFuture<Void> step = CompletableFuture.completedFuture(null);
            for (Object pendingStitch : results.rocketload_pendingStitches()) {
                PendingStitchAccessor accessor = (PendingStitchAccessor) pendingStitch;
                step = step
                    .thenCompose(unused -> AtlasPreUpload.prepare(accessor.rocketload_preparations().join()))
                    .thenAcceptAsync(job -> {
                        long start = System.nanoTime();
                        AtlasPreUpload.runWithPrepared(job, () -> accessor.rocketload_joinAndUpload(spriteMap));
                        ReloadDebug.log("atlas finalize {} ms, sprites={}", (System.nanoTime() - start) / 1_000_000, spriteMap.size());
                    }, executor);
            }
            return step.thenRunAsync(() -> {
                PREFILLED.put(results, spriteMap);
                try {
                    apply.accept(null);
                } finally {
                    PREFILLED.remove(results);
                }
            }, executor);
        }, executor);
    }
}
