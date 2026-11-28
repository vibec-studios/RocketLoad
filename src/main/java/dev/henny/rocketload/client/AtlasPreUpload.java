package dev.henny.rocketload.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if >=26.3 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
//?} else if >=26.2 {
/*import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
*///?} else {
/*import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
*///?}

public final class AtlasPreUpload {
    private static final long MIN_SLICE_NANOS = 1_000_000L;

    private static final Map<String, GpuTexture> READY = new HashMap<>();
    private static final Set<GpuTexture> PREPARED = Collections.newSetFromMap(new IdentityHashMap<>());

    private AtlasPreUpload() {
    }

    public static final class Job {
        private final List<TextureAtlasSprite> sprites;
        private final int mipLevelCount;
        private final Map<String, GpuTexture> textures = new HashMap<>();
        private int index;

        private Job(List<TextureAtlasSprite> sprites, int mipLevelCount) {
            this.sprites = sprites;
            this.mipLevelCount = mipLevelCount;
        }

        private void closeAll() {
            for (GpuTexture texture : this.textures.values()) {
                texture.close();
            }
            this.textures.clear();
        }
    }

    public static CompletableFuture<Job> prepare(SpriteLoader.Preparations preparations) {
        if (RocketStartAtlas.direct()) {
            // RocketStart writes the static sprites straight into the atlas when it is switched; scratch textures
            // for them would only cost frames now and thousands of texture deletions then.
            return CompletableFuture.completedFuture(new Job(new ArrayList<>(), preparations.mipLevel() + 1));
        }
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        for (TextureAtlasSprite sprite : preparations.regions().values()) {
            if (!sprite.contents().isAnimated()) {
                sprites.add(sprite);
            }
        }
        Job job = new Job(sprites, preparations.mipLevel() + 1);
        CompletableFuture<Job> future = new CompletableFuture<>();
        BackgroundReloadDriver.enqueueApply("atlas-preupload", () -> pump(job, future));
        return future;
    }

    private static void pump(Job job, CompletableFuture<Job> future) {
        try {
            long deadline = Math.max(BackgroundReloadDriver.frameDeadline(), System.nanoTime() + MIN_SLICE_NANOS);
            GpuDevice device = RenderSystem.getDevice();
            while (job.index < job.sprites.size() && System.nanoTime() < deadline) {
                TextureAtlasSprite sprite = job.sprites.get(job.index++);
                String name = sprite.contents().name().toString();
                GpuTexture texture = device.createTexture(
                    () -> name,
                    5,
                    //? if >=26.2 {
                    GpuFormat.RGBA8_UNORM,
                    //?} else {
                    /*TextureFormat.RGBA8,
                    *///?}
                    sprite.contents().width(),
                    sprite.contents().height(),
                    1,
                    job.mipLevelCount
                );
                job.textures.put(name, texture);
                for (int level = 0; level < job.mipLevelCount; level++) {
                    sprite.uploadFirstFrame(texture, level);
                }
            }
            if (job.index < job.sprites.size()) {
                BackgroundReloadDriver.enqueueApply("atlas-preupload", () -> pump(job, future));
                return;
            }
            ReloadDebug.log("atlas pre-upload fertig: {} Sprites", job.sprites.size());
            future.complete(job);
        } catch (Throwable t) {
            job.closeAll();
            future.completeExceptionally(t);
        }
    }

    public static void runWithPrepared(Job job, Runnable upload) {
        READY.clear();
        PREPARED.clear();
        READY.putAll(job.textures);
        PREPARED.addAll(job.textures.values());
        job.textures.clear();
        try {
            upload.run();
        } finally {
            for (GpuTexture leftover : READY.values()) {
                leftover.close();
            }
            READY.clear();
            PREPARED.clear();
        }
    }

    /**
     * True while an atlas upload uses textures RocketLoad prepared (scratch textures or a filled hidden atlas); queried by
     * RocketStart's direct atlas upload, which then steps back.
     */
    public static boolean active() {
        return !READY.isEmpty() || ShadowAtlas.claimed();
    }

    public static GpuTexture take(String name) {
        if (READY.isEmpty() || name == null) {
            return null;
        }
        return READY.remove(name);
    }

    public static boolean isPrepared(GpuTexture texture) {
        return !PREPARED.isEmpty() && PREPARED.contains(texture);
    }
}
