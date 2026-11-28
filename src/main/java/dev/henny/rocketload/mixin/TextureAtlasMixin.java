package dev.henny.rocketload.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.henny.rocketload.client.AtlasPreUpload;
import dev.henny.rocketload.client.ShadowAtlas;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.3 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//?} else if >=26.2 {
/*import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
*///?} else {
/*import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
*///?}

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {

    //? if >=26.3 {
    private static final String CREATE_TEXTURE = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;";
    private static final String UPLOAD_FIRST_FRAME = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame(Lcom/mojang/renderpearl/api/textures/GpuTexture;I)V";
    //?} else if >=26.2 {
    /*private static final String CREATE_TEXTURE = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;";
    private static final String UPLOAD_FIRST_FRAME = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame(Lcom/mojang/blaze3d/textures/GpuTexture;I)V";
    *///?} else {
    /*private static final String CREATE_TEXTURE = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;";
    private static final String UPLOAD_FIRST_FRAME = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame(Lcom/mojang/blaze3d/textures/GpuTexture;I)V";
    *///?}

    @Shadow
    private int width;

    @Shadow
    private int height;

    @Shadow
    private int maxMipLevel;

    @Shadow
    private int mipLevelCount;

    @Shadow
    private GpuTextureView[] mipViews;

    @Shadow
    private void uploadAnimationFrames() {
    }

    //? if >=26.2 {
    @Shadow
    protected abstract void releaseTextures();
    //?}

    @Inject(method = "createTexture", at = @At("HEAD"), cancellable = true)
    private void rocketload$keepAtlasTexture(int newWidth, int newHeight, int newMipLevel, CallbackInfo ci) {
        ShadowAtlas.Job shadow = ShadowAtlas.claim(newWidth, newHeight, newMipLevel);
        if (shadow != null) {
            // Same steps as vanilla's createTexture, with the texture RocketLoad already filled during the reload.
            //? if >=26.2 {
            this.releaseTextures();
            //?} else {
            /*((TextureAtlas) (Object) this).close();
            *///?}
            AbstractTextureAccessor texture = (AbstractTextureAccessor) this;
            texture.rocketload_setTexture(shadow.texture());
            texture.rocketload_setTextureView(shadow.view());
            this.width = newWidth;
            this.height = newHeight;
            this.maxMipLevel = newMipLevel;
            this.mipLevelCount = newMipLevel + 1;
            this.mipViews = shadow.mipViews();
            ci.cancel();
            return;
        }
        if (this.width > 0 && this.width == newWidth && this.height == newHeight && this.maxMipLevel == newMipLevel) {
            ci.cancel();
        }
    }

    @Inject(method = "uploadInitialContents", at = @At("HEAD"), cancellable = true)
    private void rocketload$shadowHasStatics(CallbackInfo ci) {
        if (ShadowAtlas.claimed()) {
            // All static sprites are in the switched-in texture already; animated ones are drawn as usual.
            this.uploadAnimationFrames();
            ci.cancel();
        }
    }

    @WrapOperation(method = "uploadInitialContents", at = @At(value = "INVOKE", target = CREATE_TEXTURE))
    private GpuTexture rocketload$usePreUploaded(
            GpuDevice device,
            Supplier<String> label,
            int usage,
            //? if >=26.2 {
            GpuFormat format,
            //?} else {
            /*TextureFormat format,
            *///?}
            int width,
            int height,
            int depthOrLayers,
            int mipLevels,
            Operation<GpuTexture> original) {
        GpuTexture prepared = label != null ? AtlasPreUpload.take(label.get()) : null;
        return prepared != null ? prepared : original.call(device, label, usage, format, width, height, depthOrLayers, mipLevels);
    }

    @WrapOperation(method = "uploadInitialContents", at = @At(value = "INVOKE", target = UPLOAD_FIRST_FRAME))
    private void rocketload$skipPreUploaded(TextureAtlasSprite sprite, GpuTexture texture, int level, Operation<Void> original) {
        if (!AtlasPreUpload.isPrepared(texture)) {
            original.call(sprite, texture, level);
        }
    }
}
