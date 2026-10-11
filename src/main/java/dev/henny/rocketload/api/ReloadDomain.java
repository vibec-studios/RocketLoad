package dev.henny.rocketload.api;

public enum ReloadDomain {
    SOUNDS("Sounds"),
    SPLASH_TEXTS("Splash-Texte"),
    COLORMAPS("Colormaps"),
    LANGUAGE("Sprache"),
    FONTS("Fonts"),
    SHADERS("Shader"),
    TEXTURES("Texturen"),
    VISUALS("Atlanten/Modelle/Partikel"),
    GEOMETRY("Chunk-Geometrie");

    public final String label;

    ReloadDomain(String label) {
        this.label = label;
    }
}
