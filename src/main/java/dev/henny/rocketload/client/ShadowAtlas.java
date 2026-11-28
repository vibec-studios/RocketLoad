package dev.henny.rocketload.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;
//? if >=26.3 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//?} else if >=26.2 {
/*import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
*///?} else {
/*import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
*///?}

/**
 * Builds a reloaded atlas in a second, hidden texture while the rest of the reload (model baking) is still running.
 *
 * <p>Vanilla only starts uploading an atlas once every listener has finished preparing, and then writes all sprites in
 * one go: with a large resource pack that is one long frame after the models are baked. Here the static sprites are
 * drawn into a fresh texture over many frames as soon as the atlas is stitched, with exactly the same scratch texture
 * and {@code ANIMATE_SPRITE_BLIT} pass vanilla uses. When vanilla then uploads the atlas, the finished texture simply
 * takes the place of the one vanilla would create, and only animated sprites are drawn as usual. The visible atlas
 * keeps showing the old textures until that switch, so old models never see new sprite positions.
 */
public final class ShadowAtlas {
    /** Upper bound per frame while the reload is still preparing; the switch is not waiting for us yet. */
    private static final long EARLY_SLICE_NANOS = 6_000_000L;
    private static final long MIN_SLICE_NANOS = 1_000_000L;
    private static final int MAX_BATCH = 256;

    /** Render thread only. */
    private static Job swapping;
    private static Job claimed;
    private static volatile boolean urgent;

    private ShadowAtlas() {
    }

    public static final class Job {
        private final List<TextureAtlasSprite> sprites;
        private final int width;
        private final int height;
        private final int maxMipLevel;
        private final int mipLevelCount;
        private GpuTexture texture;
        private GpuTextureView view;
        private GpuTextureView[] mipViews;
        private int index;
        private boolean finished;
        private boolean consumed;
        private long nanos;
        private int slices;

        private Job(SpriteLoader.Preparations preparations) {
            this.width = preparations.width();
            this.height = preparations.height();
            this.maxMipLevel = preparations.mipLevel();
            this.mipLevelCount = preparations.mipLevel() + 1;
            List<TextureAtlasSprite> statics = new ArrayList<>();
            for (TextureAtlasSprite sprite : preparations.regions().values()) {
                if (!sprite.contents().isAnimated()) {
                    statics.add(sprite);
                }
            }
            this.sprites = statics;
        }

        public GpuTexture texture() {
            return this.texture;
        }

        public GpuTextureView view() {
            return this.view;
        }

        public GpuTextureView[] mipViews() {
            return this.mipViews;
        }

        private void close() {
            if (this.mipViews != null) {
                for (GpuTextureView mip : this.mipViews) {
                    if (mip != null) mip.close();
                }
                this.mipViews = null;
            }
            if (this.view != null) {
                this.view.close();
                this.view = null;
            }
            if (this.texture != null) {
                this.texture.close();
                this.texture = null;
            }
        }
    }

    public static boolean enabled() {
        return dev.henny.rocketload.RocketLoadConfig.getInstance().getShadowAtlasUpload();
    }

    /** Starts filling a hidden atlas texture over the coming frames; completes with null if that is not possible. */
    public static CompletableFuture<Job> build(SpriteLoader.Preparations preparations) {
        CompletableFuture<Job> future = new CompletableFuture<>();
        Job job;
        try {
            job = new Job(preparations);
        } catch (Throwable t) {
            ReloadDebug.log("Schatten-Atlas nicht möglich: {}", t.toString());
            future.complete(null);
            return future;
        }
        BackgroundReloadDriver.enqueueApply("atlas-shadow", () -> pump(job, future));
        return future;
    }

    /** From now on the switch waits for the hidden atlases: use whole frame budgets. */
    public static void markUrgent() {
        urgent = true;
    }

    private static void pump(Job job, CompletableFuture<Job> future) {
        long start = System.nanoTime();
        try {
            long deadline = Math.max(BackgroundReloadDriver.frameDeadline(), start + MIN_SLICE_NANOS);
            if (!urgent) deadline = Math.min(deadline, start + EARLY_SLICE_NANOS);
            if (job.texture == null) {
                create(job);
            }
            while (job.index < job.sprites.size() && System.nanoTime() < deadline) {
                drawBatch(job, deadline);
            }
            job.slices++;
            job.nanos += System.nanoTime() - start;
            if (job.index < job.sprites.size()) {
                BackgroundReloadDriver.enqueueApply("atlas-shadow", () -> pump(job, future));
                return;
            }
            job.finished = true;
            ReloadDebug.log("Schatten-Atlas {}x{} fertig: {} Sprites, {} ms in {} Frames", job.width, job.height,
                    job.sprites.size(), job.nanos / 1_000_000, job.slices);
            future.complete(job);
        } catch (Throwable t) {
            ReloadDebug.log("Schatten-Atlas abgebrochen, normaler Upload: {}", t.toString());
            job.close();
            future.complete(null);
        }
    }

    private static void create(Job job) {
        GpuDevice device = RenderSystem.getDevice();
        String label = "rocketload:shadow-atlas " + job.width + "x" + job.height;
        //? if >=26.2 {
        job.texture = device.createTexture(() -> label, 15, GpuFormat.RGBA8_UNORM, job.width, job.height, 1, job.mipLevelCount);
        //?} else {
        /*job.texture = device.createTexture(() -> label, 15, TextureFormat.RGBA8, job.width, job.height, 1, job.mipLevelCount);
        *///?}
        job.view = device.createTextureView(job.texture);
        job.mipViews = new GpuTextureView[job.mipLevelCount];
        for (int level = 0; level <= job.maxMipLevel; level++) {
            job.mipViews[level] = device.createTextureView(job.texture, level, 1);
        }
    }

    /** One slice of vanilla's {@code TextureAtlas.uploadInitialContents}, drawn into the hidden texture. */
    private static void drawBatch(Job job, long deadline) {
        GpuDevice device = RenderSystem.getDevice();
        //? if >=26.2 {
        int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
        //?} else {
        /*int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getUniformOffsetAlignment());
        *///?}
        int uboBlockSize = spriteUboSize * job.mipLevelCount;
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
        List<GpuTextureView[]> scratchTextures = new ArrayList<>();
        ByteBuffer buffer = MemoryUtil.memAlloc(MAX_BATCH * uboBlockSize);
        try {
            while (job.index < job.sprites.size() && scratchTextures.size() < MAX_BATCH
                    && (scratchTextures.isEmpty() || System.nanoTime() < deadline)) {
                TextureAtlasSprite sprite = job.sprites.get(job.index++);
                sprite.uploadSpriteUbo(buffer, scratchTextures.size() * uboBlockSize, job.maxMipLevel, job.width, job.height, spriteUboSize);
                String name = sprite.contents().name().toString();
                GpuTexture scratch = device.createTexture(
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
                GpuTextureView[] views = new GpuTextureView[job.mipLevelCount];
                scratchTextures.add(views);
                for (int level = 0; level <= job.maxMipLevel; level++) {
                    sprite.uploadFirstFrame(scratch, level);
                    views[level] = device.createTextureView(scratch);
                }
            }
            buffer.limit(scratchTextures.size() * uboBlockSize);
            try (GpuBuffer ubo = device.createBuffer(() -> "SpriteAnimationInfo", 128, buffer)) {
                for (int level = 0; level < job.mipLevelCount; level++) {
                    try (RenderPass renderPass = device.createCommandEncoder()
                            //? if >=26.2 {
                            .createRenderPass(() -> "RocketLoad shadow atlas", job.mipViews[level], Optional.empty())) {
                        RenderSystem.bindDefaultUniforms(renderPass);
                        //?} else {
                            /*.createRenderPass(() -> "RocketLoad shadow atlas", job.mipViews[level], OptionalInt.empty())) {
                        *///?}
                        //? if >=26.3 {
                        renderPass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT));
                        //?} else {
                        /*renderPass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT);
                        *///?}
                        for (int i = 0; i < scratchTextures.size(); i++) {
                            //? if >=26.3 {
                            renderPass.setUniform("Sprite", scratchTextures.get(i)[level], sampler);
                            //?} else {
                            /*renderPass.bindTexture("Sprite", scratchTextures.get(i)[level], sampler);
                            *///?}
                            renderPass.setUniform("SpriteAnimationInfo", ubo.slice(i * uboBlockSize + level * spriteUboSize, SpriteContents.UBO_SIZE));
                            //? if >=26.2 {
                            renderPass.draw(6, 1, 0, 0);
                            //?} else {
                            /*renderPass.draw(0, 6);
                            *///?}
                        }
                    }
                }
            }
        } finally {
            for (GpuTextureView[] views : scratchTextures) {
                GpuTexture scratch = null;
                for (GpuTextureView view : views) {
                    if (view == null) continue;
                    scratch = view.texture();
                    view.close();
                }
                if (scratch != null) scratch.close();
            }
            MemoryUtil.memFree(buffer);
        }
    }

    /** Runs vanilla's upload of one atlas; if {@code job} is finished, its texture becomes the atlas texture. */
    public static void runWithShadow(Job job, Runnable upload) {
        swapping = job != null && job.finished ? job : null;
        try {
            upload.run();
        } finally {
            swapping = null;
            claimed = null;
            if (job != null && !job.consumed) {
                job.close();
            }
        }
    }

    /** Called when vanilla is about to create the atlas texture: the hidden one if it fits, else null. */
    public static Job claim(int width, int height, int maxMipLevel) {
        Job job = swapping;
        if (job == null || job.consumed || job.texture == null) return null;
        if (job.width != width || job.height != height || job.maxMipLevel != maxMipLevel) return null;
        job.consumed = true;
        claimed = job;
        return job;
    }

    /** True while the atlas being uploaded already holds all static sprites (queried before the initial blit). */
    public static boolean claimed() {
        return claimed != null;
    }

    /** Closes a hidden atlas that never got switched in (failed or cancelled reload). Render thread. */
    public static void discard(Job job) {
        if (job != null && !job.consumed) {
            job.close();
        }
    }

    public static void endReload() {
        urgent = false;
    }
}
