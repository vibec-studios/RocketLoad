package dev.henny.rocketload.mixin;

import java.util.Map;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.texture.AtlasManager$CompletableEntry")
public interface AtlasManagerCompletableEntryInvoker {

    @Invoker("fillSpriteMap")
    void rocketload_fillSpriteMap(Map<SpriteIdentifier, Sprite> sprites);
}
