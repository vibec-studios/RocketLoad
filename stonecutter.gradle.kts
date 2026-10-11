plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3"

stonecutter parameters {
    replacements {
        string(current.parsed < "26.1") {
            replace("net.minecraft.client.resources.model.sprite.AtlasManager", "net.minecraft.client.resources.model.AtlasManager")
        }
    }
}
