package dev.henny.rocketload.api;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

@FunctionalInterface
public interface EntryFilter {
    boolean test(PackEntry entry);

    default EntryFilter and(EntryFilter other) {
        return entry -> this.test(entry) && other.test(entry);
    }

    default EntryFilter or(EntryFilter other) {
        return entry -> this.test(entry) || other.test(entry);
    }

    default EntryFilter negate() {
        return entry -> !this.test(entry);
    }

    static EntryFilter all() {
        return entry -> true;
    }

    static EntryFilter none() {
        return entry -> false;
    }

    static EntryFilter categories(AssetCategory first, AssetCategory... rest) {
        Set<AssetCategory> set = EnumSet.of(first, rest);
        return entry -> set.contains(entry.category());
    }

    static EntryFilter textures() {
        return categories(AssetCategory.TEXTURE, AssetCategory.TEXTURE_METADATA, AssetCategory.COLORMAP);
    }

    static EntryFilter models() {
        return categories(AssetCategory.MODEL, AssetCategory.BLOCKSTATE, AssetCategory.ITEM_MODEL);
    }

    static EntryFilter sounds() {
        return categories(AssetCategory.SOUND, AssetCategory.SOUND_DEFINITION);
    }

    static EntryFilter packMeta() {
        return categories(AssetCategory.PACK_META);
    }

    static EntryFilter namespaces(String... namespaces) {
        Set<String> set = Set.of(namespaces);
        return entry -> entry.namespace().map(set::contains).orElse(false);
    }

    static EntryFilter prefix(String prefix) {
        return entry -> entry.path().startsWith(prefix);
    }

    static EntryFilter suffix(String suffix) {
        return entry -> entry.path().endsWith(suffix);
    }

    static EntryFilter regex(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return entry -> pattern.matcher(entry.path()).matches();
    }

    static EntryFilter glob(String glob) {
        return regex(globToRegex(glob));
    }

    static EntryFilter maxSize(long bytes) {
        return entry -> entry.size() <= bytes;
    }

    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        int depth = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        out.append("(?:.*/)?");
                    } else {
                        out.append(".*");
                    }
                } else {
                    out.append("[^/]*");
                }
            } else if (c == '?') {
                out.append("[^/]");
            } else if (c == '{') {
                depth++;
                out.append("(?:");
            } else if (c == '}' && depth > 0) {
                depth--;
                out.append(')');
            } else if (c == ',' && depth > 0) {
                out.append('|');
            } else if (Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('\\').append(c);
            }
        }
        return out.append('$').toString();
    }
}
