package dev.henny.rocketload.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

    @Accessor("texturesByName")
    Map<Identifier, TextureAtlasSprite> rocketload_texturesByName();

    @Accessor("width")
    int rocketload_width();

    @Accessor("height")
    int rocketload_height();
}
