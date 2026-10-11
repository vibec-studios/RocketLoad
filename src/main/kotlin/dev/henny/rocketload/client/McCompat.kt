package dev.henny.rocketload.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.client.gui.screens.Screen
//? if <26.2 {
/*import dev.henny.rocketload.mixin.LevelRendererAccessor
import dev.henny.rocketload.mixin.ViewAreaAccessor
import net.minecraft.core.SectionPos
*///?}

object McCompat {

    @JvmStatic
    fun toastManager(mc: Minecraft): ToastManager {
        //? if >=26.2 {
        return mc.gui.toastManager()
        //?} else {
        /*return mc.toastManager
        *///?}
    }

    @JvmStatic
    fun overlay(mc: Minecraft): Overlay? {
        //? if >=26.2 {
        return mc.gui.overlay()
        //?} else {
        /*return mc.overlay
        *///?}
    }

    @JvmStatic
    fun screen(mc: Minecraft): Screen? {
        //? if >=26.2 {
        return mc.gui.screen()
        //?} else {
        /*return mc.screen
        *///?}
    }

    @JvmStatic
    fun fullTerrainRebuild(mc: Minecraft) {
        //? if >=26.2 {
        mc.levelExtractor.allChanged()
        //?} else {
        /*mc.levelRenderer.allChanged()
        *///?}
    }

    @JvmStatic
    fun softTerrainRebuild(mc: Minecraft): Boolean {
        //? if >=26.2 {
        return false
        //?} else {
        /*val level = mc.level ?: return false
        val viewArea = (mc.levelRenderer as LevelRendererAccessor).rocketload_viewArea() ?: return false
        val sections = (viewArea as ViewAreaAccessor).rocketload_sections() ?: return false
        level.clearTintCaches()
        var marked = 0
        for (section in sections) {
            if (section == null) continue
            val node = section.sectionNode
            mc.levelRenderer.setSectionDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node))
            marked++
        }
        ReloadDebug.log("soft terrain rebuild: {} sections", marked)
        return true
        *///?}
    }
}
