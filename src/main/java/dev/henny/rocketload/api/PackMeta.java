package dev.henny.rocketload.api;

import java.util.List;
import java.util.OptionalInt;

public record PackMeta(String description, OptionalInt minFormat, OptionalInt maxFormat, List<String> overlays, String rawJson) {
}
