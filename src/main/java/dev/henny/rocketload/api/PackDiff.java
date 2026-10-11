package dev.henny.rocketload.api;

import java.util.List;

public record PackDiff(List<String> added, List<String> removed, List<String> changed, List<String> unchanged) {
    public boolean isEmpty() {
        return this.added.isEmpty() && this.removed.isEmpty() && this.changed.isEmpty();
    }
}
