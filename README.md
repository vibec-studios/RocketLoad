# 🚀 RocketLoad

**Loads your resourcepacks like a rocketship.**

Client-Mod für Fabric (Minecraft 1.21.11), die die komplette Resourcepack-Pipeline beschleunigt: Packs werden in ein optimiertes Cacheformat (`.rocketfuel`) konvertiert, Reloads laufen ohne Splash-Screen im Hintergrund, und alles, was sich nicht geändert hat, wird gar nicht erst neu geladen.

## Features

### ⛽ `.rocketfuel`-Cache
Zip-Packs werden beim ersten Kontakt (bzw. beim Spielstart im Hintergrund) einmalig konvertiert und danach memory-mapped gelesen:

- **Unkomprimiert + indiziert** — kein Zip-Scan, keine Dekompression, Namespace- und Verzeichnis-Listings kommen direkt aus dem vorgeladenen Index
- **Junk-Filter** — `__MACOSX/`, `.DS_Store`, `Thumbs.db`, `desktop.ini`, `.git*` fliegen raus
- **JSON-Minify** — Models/Blockstates/`.mcmeta` werden kompaktiert (nur bei strikt gültigem JSON)
- **Dedupe** — identische Dateien teilen sich einen Datenblock
- Cache liegt unter `<gameDir>/rocketload/cache/`, Validierung über Größe + mtime der Quelldatei; geänderte Packs werden automatisch neu konvertiert
- Konvertierung läuft **nie auf dem Render-Thread** (paralleler Worker-Pool), Housekeeping löscht Cachedateien, die konfigurierbar lange nicht benutzt wurden

### 🔄 Reload ohne Splash-Screen
Manuelle Reloads (F3+T, Pack-Screen, Server-Packs) zeigen keinen Mojang-Splash mehr: Das Spiel rendert weiter, der Reload läuft im Hintergrund, ein Toast zeigt den Fortschritt. Der Recovery-Pfad (kaputtes Pack → Rollback) behält bewusst das Vanilla-Verhalten.

### 🧱 Kein unnötiger Chunk-Rebuild
Nach dem Reload prüft RocketLoad zweistufig, ob der komplette Chunk-Rebuild (`WorldRenderer.reload`) nötig ist:

1. **Geometrie-Fingerprint** — nur Packs mit `blockstates/`, `models/`, `atlases/`, `shaders/` oder `textures/colormap/` zählen
2. **Block-Atlas-Layout** — Hash über die geordnete Sprite-Liste (Namen, Positionen, Größen) des frisch gestitchten Atlas

Ein Texture-Pack, das nur Pixel in gleicher Auflösung ersetzt, ändert weder Geometrie noch Layout → **kein Rebuild, kein Ruckeln**. Ordner-Packs und ungecachte Zips zählen konservativ immer als geändert.

### ⏭️ Selektiver Reload
Vanilla lädt bei jedem Reload *alle* Listener neu. RocketLoad überspringt die, deren Ressourcen sich nicht geändert haben:

| Listener | Domäne | Effekt beim Skip |
|---|---|---|
| SoundManager | `sounds/`, `sounds.json` | **Musik läuft weiter**, kein Engine-Neustart |
| FontManager | `font/`, `textures/font/` | Kein Font-Rebuild (einer der teuersten Schritte) |
| LanguageManager | `lang/` + gewählte Sprache | Übersetzungen bleiben stehen |
| SplashManager | `texts/` | — |
| Grass-/Foliage-/DryFoliage-Colormaps | `textures/colormap/` | — |

Unbekannte Listener (Mods) laufen immer normal. Im Zweifel (Ordner-Pack, Fehler) wird immer neu geladen — nie weggelassen.

## Konfiguration

`config/rocketload.json` (wird beim ersten Start angelegt):

| Option | Default | Bedeutung |
|---|---|---|
| `backgroundReload` | `true` | Reloads ohne Splash-Screen im Hintergrund |
| `applyBudgetMsPerTick` | `3` | Max. Millisekunden Apply-Arbeit pro Frame, höchstens ein Task. Unveränderte Packs werden nach dem ersten Laden übersprungen. Chunk-Rebuild läuft nicht im selben Frame wie Atlas-Upload. |
| `toasts` | `true` | Fortschritts-/Abschluss-Toasts |
| `chunkRebuildGuard` | `true` | Chunk-Rebuild nur bei echter Änderung |
| `selectiveReload` | `true` | Unveränderte Listener überspringen |
| `warmupConversion` | `true` | Packs beim Start vorkonvertieren |
| `jsonMinify` | `true` | JSON beim Konvertieren minifizieren |
| `deduplicate` | `true` | Identische Dateien deduplizieren |
| `cacheRetentionDays` | `14` | Unbenutzte Cachedateien löschen (0 = nie) |

Hinweis: Änderungen an `jsonMinify`/`deduplicate` wirken erst bei der nächsten Konvertierung (bestehende Caches bleiben gültig).

## Entwicklung

- **Build**: `gradlew build` — Java 21+ (Pfad in `gradle.properties` via `org.gradle.java.home`)
- **Testlauf**: `gradlew runClient`; mit Env-Var `ROCKETLOAD_AUTO_RELOAD=1` löst die Mod ~10 s nach Start automatisch einen Reload aus
- MC 1.21.11 nutzt Yarn-Mappings und das Loom-Plugin `net.fabricmc.fabric-loom-remap`

### `.rocketfuel`-Format (v1, Writer v2)

```
Header (44 B): magic "RKTFUEL\x01" | version | sourceSize | sourceMtime | indexOffset | entryCount | writerVersion
Daten:         Rohbytes aller Einträge, unkomprimiert, hintereinander (dedupliziert)
Index:         pro Eintrag: pathLen | path (UTF-8, Zip-Stil) | offset | size
```

Alles Big-Endian. Die Datei wird über eine shared `Arena` (FFM-API) gemappt, damit `close()` das Mapping deterministisch freigibt — wichtig auf Windows, wo gemappte Dateien nicht ersetzt werden können.

## Lizenz

MIT
