package dev.henny.rocketload.api;

import java.util.Locale;

public enum AssetCategory {
    PACK_META,
    TEXTURE,
    TEXTURE_METADATA,
    COLORMAP,
    MODEL,
    BLOCKSTATE,
    ITEM_MODEL,
    ATLAS,
    PARTICLE,
    SOUND,
    SOUND_DEFINITION,
    LANGUAGE,
    FONT,
    SHADER,
    POST_EFFECT,
    TEXT,
    EQUIPMENT,
    DATA,
    OTHER;

    public static AssetCategory of(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.indexOf('/') < 0) {
            return lower.startsWith("pack.") ? PACK_META : OTHER;
        }
        if (lower.startsWith("data/")) {
            return DATA;
        }
        if (!lower.startsWith("assets/")) {
            return OTHER;
        }
        int nsEnd = lower.indexOf('/', 7);
        if (nsEnd < 0) {
            return OTHER;
        }
        int dirEnd = lower.indexOf('/', nsEnd + 1);
        String dir = dirEnd < 0 ? lower.substring(nsEnd + 1) : lower.substring(nsEnd + 1, dirEnd);
        String rest = dirEnd < 0 ? "" : lower.substring(dirEnd + 1);
        return switch (dir) {
            case "textures" -> {
                if (rest.endsWith(".mcmeta")) {
                    yield TEXTURE_METADATA;
                }
                yield rest.startsWith("colormap/") ? COLORMAP : TEXTURE;
            }
            case "models" -> MODEL;
            case "blockstates" -> BLOCKSTATE;
            case "items" -> ITEM_MODEL;
            case "atlases" -> ATLAS;
            case "particles" -> PARTICLE;
            case "sounds" -> SOUND;
            case "sounds.json" -> SOUND_DEFINITION;
            case "lang" -> LANGUAGE;
            case "font" -> FONT;
            case "shaders" -> SHADER;
            case "post_effect" -> POST_EFFECT;
            case "texts" -> TEXT;
            case "equipment" -> EQUIPMENT;
            default -> OTHER;
        };
    }
}
