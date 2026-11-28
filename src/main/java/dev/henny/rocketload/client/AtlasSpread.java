package dev.henny.rocketload.client;

import dev.henny.rocketload.mixin.PendingStitchAccessor;
import java.util.ArrayList;
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
        // Hidden atlases start filling as soon as each atlas is stitched, while models are still baking.
        List<?> stitches = results.rocketload_pendingStitches();
        List<CompletableFuture<ShadowAtlas.Job>> shadows = new ArrayList<>();
        if (ShadowAtlas.enabled()) {
            for (Object pendingStitch : stitches) {
                PendingStitchAccessor accessor = (PendingStitchAccessor) pendingStitch;
                shadows.add(accessor.rocketload_preparations().thenCompose(ShadowAtlas::build).exceptionally(t -> null));
            }
        }
        CompletableFuture<Void> done = prepared.thenComposeAsync(ignored -> {
            ShadowAtlas.markUrgent();
            Map<Object, Object> spriteMap = new HashMap<>();
            CompletableFuture<Void> step = CompletableFuture.completedFuture(null);
            for (int i = 0; i < stitches.size(); i++) {
                PendingStitchAccessor accessor = (PendingStitchAccessor) stitches.get(i);
                if (!shadows.isEmpty()) {
                    CompletableFuture<ShadowAtlas.Job> shadow = shadows.get(i);
                    step = step
                        .thenCompose(unused -> shadow)
                        .thenAcceptAsync(job -> {
                            long start = System.nanoTime();
                            ShadowAtlas.runWithShadow(job, () -> accessor.rocketload_joinAndUpload(spriteMap));
                            ReloadDebug.log("atlas switch {} ms (schatten={}), sprites={}", (System.nanoTime() - start) / 1_000_000,
                                    job != null, spriteMap.size());
                        }, executor);
                    continue;
                }
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
        // A failed reload never switches its hidden atlases in: free them.
        done.whenComplete((unused, error) -> {
            ShadowAtlas.endReload();
            if (error == null || shadows.isEmpty()) return;
            for (CompletableFuture<ShadowAtlas.Job> shadow : shadows) {
                shadow.thenAccept(job -> BackgroundReloadDriver.enqueueApply("atlas-shadow-free", () -> ShadowAtlas.discard(job)));
            }
        });
        return done;
    }
}
