package dev.henny.rocketload.mixin;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Map;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpriteAtlasTexture.class)
public interface SpriteAtlasTextureAccessor {

    @Accessor("sprites")
    Map<Identifier, Sprite> rocketload_getSprites();

    @Accessor("spritesToLoad")
    List<Sprite> rocketload_getSpritesToLoad();

    @Accessor("width")
    int rocketload_getWidth();

    @Accessor("height")
    int rocketload_getHeight();

    @Accessor("mipLevel")
    int rocketload_getMipLevel();

    @Accessor("numMipLevels")
    int rocketload_getNumMipLevels();

    @Accessor("mipTextures")
    GpuTextureView[] rocketload_getMipTextures();

    @Invoker("uploadAnimations")
    void rocketload_uploadAnimations();
}
