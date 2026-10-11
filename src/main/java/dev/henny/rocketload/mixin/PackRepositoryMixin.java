package dev.henny.rocketload.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.henny.rocketload.RocketLoadHooks;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    @WrapMethod(method = "openAllSelected")
    private List<PackResources> rocketload$openWithFuel(Operation<List<PackResources>> original) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean clientRepository = minecraft != null && minecraft.getResourcePackRepository() == (Object) this;
        if (!clientRepository) {
            return original.call();
        }
        RocketLoadHooks.beginFullOpen();
        try {
            return original.call();
        } finally {
            RocketLoadHooks.endFullOpen();
        }
    }
}
