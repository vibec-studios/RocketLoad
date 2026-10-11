package dev.henny.rocketload.api;

@FunctionalInterface
public interface SkipVeto {
    boolean vetoSkip(ReloadDomain domain);
}
