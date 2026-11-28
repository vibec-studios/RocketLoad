# 🚀 RocketLoad

**Loads your resourcepacks like a rocketship.**

Client-Mod für Fabric, die die komplette Resourcepack-Pipeline beschleunigt: Packs werden in ein optimiertes Cacheformat (`.rocketfuel`) konvertiert, Reloads laufen ohne Splash-Screen im Hintergrund, und alles, was sich nicht geändert hat, wird gar nicht erst neu geladen.

**Unterstützte Versionen:** Minecraft **1.21.11**, **26.1.x**, **26.2** und **26.3** – aus einem einzigen Codestand (Stonecutter).

## Features

### ⛽ `.rocketfuel`-Cache
Zip-Packs werden beim Spielstart im Hintergrund einmalig konvertiert:

- **Unkomprimiert + indiziert** – kein Zip-Scan, keine Dekompression, Namespace- und Verzeichnis-Listings kommen direkt aus dem Index
- **Parallele Konvertierung** – JSON/PNG-Optimierung läuft auf mehreren Threads, geschrieben wird in Originalreihenfolge
- **Junk-Filter** – `__MACOSX/`, `.DS_Store`, `Thumbs.db`, `desktop.ini`, `.git*` fliegen raus
- **JSON-Minify** – nur bei strikt gültigem JSON, alles andere bleibt bytegleich
- **PNG-Optimierung** – Metadaten-Chunks raus, Bilddaten neu komprimiert (pixelidentisch, nur übernommen wenn kleiner)
- **Dedupe** – identische Dateien teilen sich einen Datenblock
- **Direkter Textur-Pfad** – Texturen werden ohne Zwischenkopie aus dem Cache in den nativen Decoder gelesen
- Interrupt-sicher: Ein unterbrochener Thread kann den Cache nicht mehr für alle anderen schließen
- Cache unter `<gameDir>/rocketload/cache/`, Dateiname enthält Größe + mtime der Quelle → geänderte Packs bekommen eine neue Datei, alte werden aufgeräumt (keine Windows-Dateisperren-Probleme)

### 🔄 Reload ohne Splash-Screen
Manuelle Reloads (F3+T, Pack-Screen, Server-Packs) zeigen keinen Mojang-Splash: Das Spiel rendert weiter, ein Toast zeigt den Fortschritt, die Apply-Arbeit wird mit Zeitbudget über mehrere Frames verteilt.

- Wird während eines laufenden Reloads ein weiterer angefordert (z. B. Server-Pack kommt rein), läuft danach **automatisch ein Folge-Reload** – nichts geht verloren
- Beim Serverbeitritt startet der Pack-Reload früher und bekommt mehr Zeit pro Frame, solange noch keine Welt geladen ist. RocketLoad erkennt die betroffenen Bereiche verifizierter Server-Packs bereits im ZIP und erkennt denselben Inhalt später auch im `.rocketfuel`-Cache wieder.
- **Schatten-Atlas:** Sobald ein Atlas zusammengesetzt ist, zeichnet RocketLoad ihn über viele Frames in eine zweite, unsichtbare Textur – während die Modelle noch im Hintergrund gebacken werden. Vanilla würde erst danach alles in einem Rutsch hochladen. Am Ende wird nur noch die fertige Textur eingesetzt; das Umschalten passiert in einem einzigen Frame → kein Textur-Flackern, pixelgleich mit Vanilla (gleicher Blit-Shader)
- Die Singleplayer-Welt pausiert während des Reloads – **nicht** bei LAN-Welten mit Mitspielern
- Der Recovery-Pfad (kaputtes Pack → Rollback) behält bewusst das Vanilla-Verhalten

### ⏭️ Selektiver Reload
Vanilla lädt bei jedem Reload *alle* Listener neu. RocketLoad überspringt die, deren Ressourcen sich nicht geändert haben:

| Listener | Domäne | Effekt beim Skip |
|---|---|---|
| SoundManager | `sounds/`, `sounds.json` | Musik läuft weiter |
| FontManager | `font/`, `textures/font/` | Kein Font-Rebuild |
| LanguageManager | `lang/` + gewählte Sprache | Übersetzungen bleiben stehen |
| TextureManager | `textures/` | Keine Einzeltexturen neu |
| ShaderManager | `shaders/`, `post_effect/` | Keine Shader-Kompilierung |
| Atlanten + Modelle + Partikel | `textures/`, `models/`, `blockstates/`, `items/`, `atlases/`, `particles/` + Mipmap-Stufe | Kein Stitching, kein Model-Bake |
| Splash-Texte, Colormaps | `texts/`, `textures/colormap/` | — |

Atlanten, Modelle und Partikel werden **immer gemeinsam** entschieden (Vanilla koppelt sie über `AtlasManager.PENDING_STITCH`, getrennte Entscheidungen würden den Reload für immer hängen lassen).

Ändert sich die Liste der aktiven Packs, zählt nur, ob ein Pack, das den jeweiligen Bereich betrifft, dazukam, wegfiel oder verschoben wurde (abschaltbar über `selectivePackSetReload`).

Konservativ: Ordner-Packs gelten immer als geändert, unbekannte Asset-Ordner (z. B. `optifine/` für Continuity/CIT) zählen als visuell relevant, unbekannte Listener anderer Mods laufen immer.

### 🧱 Kein unnötiger Chunk-Rebuild
Nach dem Reload prüft RocketLoad, ob Block-Geometrie (`models/`, `blockstates/`, Colormaps) oder das Layout des Block-Atlas sich geändert haben. Ein Texture-Pack, das nur Pixel in gleicher Auflösung ersetzt → **kein Rebuild**. Auf 1.21.11/26.1 werden nötige Rebuilds im Hintergrund gemacht, die alten Chunk-Meshes bleiben bis dahin sichtbar.

## Konfiguration

`config/rocketload.json` (wird beim ersten Start angelegt):

| Option | Default | Bedeutung |
|---|---|---|
| `backgroundReload` | `true` | Reloads ohne Splash-Screen im Hintergrund |
| `applyBudgetMsPerTick` | `3` | Start-Budget für Apply-Arbeit pro Frame in ms (0 = Vanilla-Verhalten). Dauert ein Reload länger als 1,5 s, verdoppelt sich das Budget jede Sekunde bis 40 ms; ohne Fensterfokus gilt praktisch kein Limit |
| `spreadAtlasUpload` | `true` | Atlas-Texturen über mehrere Frames vorab hochladen |
| `preferDirectAtlasUpload` | `true` | Ist RocketStart installiert, schreibt dessen direkter Atlas-Upload die Sprites beim Umschalten in einem Rutsch; das Vorladen einzelner Scratch-Texturen entfällt |
| `shadowAtlasUpload` | `true` | Neue Atlanten schon während des Model-Bakes in eine unsichtbare zweite Textur zeichnen und beim Umschalten nur noch einsetzen. Braucht kurzzeitig den Grafikspeicher eines zweiten Atlas; aus = Upload erst nach dem Bake |
| `toasts` | `true` | Fortschritts-/Abschluss-Toasts |
| `chunkRebuildGuard` | `true` | Chunk-Rebuild nur bei echter Änderung |
| `selectiveReload` | `true` | Unveränderte Listener überspringen |
| `selectivePackSetReload` | `true` | Auch beim Aktivieren/Deaktivieren von Packs nur die betroffenen Bereiche neu laden (und keinen Chunk-Rebuild, wenn Geometrie und Block-Atlas gleich bleiben). Packs mit Overlays oder `filter` zählen dabei immer als betroffen. Aus = altes Verhalten (voller Reload) |
| `optimizeTextures` | `true` | PNGs beim Konvertieren optimieren |
| `pngCompressionLevel` | `6` | Deflate-Stufe 1–9 für PNGs (siehe Benchmark) |
| `warmupConversion` | `true` | Packs beim Start vorkonvertieren |
| `jsonMinify` | `true` | JSON beim Konvertieren minifizieren |
| `deduplicate` | `true` | Identische Dateien deduplizieren |
| `cacheRetentionDays` | `14` | Unbenutzte Cachedateien löschen (0 = nie) |
| `debugTimings` | `false` | Ausführliches Timing-Log |
| `pauseIntegratedServerDuringReload` | `true` | Singleplayer-Welt während des Reloads pausieren |

Änderungen an `jsonMinify`/`deduplicate`/`optimizeTextures` wirken bei der nächsten Konvertierung (Button „Optimieren" im Pack-Screen oder nächster Start).

## Benchmark: PNG-Kompression

Gemessen mit Minecrafts eigenem Decoder (stb_image, LWJGL), Dekodierzeit aller PNGs single-threaded:

| Pack | Original | Level 9 | Level 6 | Level 0 |
|---|---|---|---|---|
| Faithful 64x (4652 PNGs) | 343 ms / 14,0 MiB | 306 ms / 11,5 MiB, **38 s Konvertierung** | 318 ms / 12,3 MiB, 3,5 s | 198 ms / **217 MiB** |
| Bare Bones (2843 PNGs) | 69 ms / 10,3 MiB | 64 ms / 2,2 MiB, 4,1 s | 67 ms / 2,4 MiB, 0,5 s | 43 ms / 32 MiB |
| Vanilla 1.21.11 (3517 PNGs) | 39 ms / 2,0 MiB | 40 ms / 2,1 MiB, 3,5 s | 37 ms / 2,1 MiB, 0,25 s | 15 ms / 11,8 MiB |

Fazit: Level 6 bringt fast denselben Effekt wie Level 9 bei ~10× schnellerer Konvertierung, deshalb ist er jetzt Default. Unkomprimiert (Level 0) dekodiert ~40 % schneller, kostet aber das 15-fache an Platz – das lohnt sich nicht, weil Minecraft ohnehin parallel dekodiert.

## API für andere Mods

RocketLoad bringt eine versionsunabhängige Java-API mit (`dev.henny.rocketload.api`, keine Minecraft-Typen in den Signaturen):

```java
RocketLoadApi api = RocketLoadApi.get();

try (PackArchive pack = api.open(Path.of("resourcepacks/Faithful.zip"))) {
    pack.extract(EntryFilter.textures(), Path.of("export/faithful-textures"));

    pack.toBuilder(EntryFilter.textures().and(EntryFilter.namespaces("minecraft")))
        .description("Nur Faithful-Texturen")
        .writeRocketFuel(Path.of("export/faithful-textures.rocketfuel"));
}
```

| Bereich | Möglichkeiten |
|---|---|
| **Öffnen** | `open(path)` für Zip, Ordner oder `.rocketfuel`; `openCached(zip)` liefert den fertigen Cache |
| **Lesen** | `entries()`, `entries(filter)`, `read(path)`, `open(path)`, `namespaces("assets")`, `meta()` (Beschreibung, Formate, Overlays) |
| **Filtern** | `EntryFilter.textures()`, `models()`, `sounds()`, `categories(...)`, `namespaces(...)`, `prefix/suffix`, `glob("assets/*/textures/**/*.png")`, `regex`, `maxSize`, kombinierbar mit `and/or/negate` |
| **Kategorien** | `AssetCategory` erkennt Texturen, Textur-Metadaten, Colormaps, Modelle, Blockstates, Item-Modelle, Atlanten, Partikel, Sounds, Sprache, Fonts, Shader, Post-Effekte, Texte, Equipment, Data |
| **Bauen** | `newPack()`, `toBuilder(filter)`, `merge(archives, filter)`; `put`, `putAll`, `putDirectory`, `remove`, `relocate`, `renameNamespace`, `transform`, `conflictPolicy`, `packFormat`, `description`, `optimize` |
| **Schreiben** | `writeRocketFuel`, `writeZip`, `writeDirectory`, `build()` (im Speicher) |
| **Vergleichen** | `diff(before, after)` → hinzugefügt / entfernt / geändert / gleich |
| **Cache** | `convert(zip)` (async), `cacheDirectory()` |
| **Spielzustand** | `availablePacks()`, `activePackIds()`, `packFile(id)`, `reloadStatus()`, `requestReload()` |
| **Events** | `addListener(RocketLoadListener)`: Reload gestartet/fertig, Skip-Entscheidungen, Pack konvertiert |
| **Einstellungen** | `selectivePackSetReload()` / `setSelectivePackSetReload(bool)` (API 2) – ändert `config/rocketload.json` |
| **Kompatibilität** | `addSkipVeto(domain -> ...)`: Mods können erzwingen, dass ein Bereich (z. B. `VISUALS`) neu geladen wird |

## Entwicklung

- **Build aller Versionen**: `gradlew build` → Jars unter `versions/<mc>/build/libs/`; `gradlew buildAndCollect` sammelt sie in `build/libs/<mod-version>/`
- **Eine Version**: `gradlew :1.21.11:build`, `gradlew :26.3:runClient`
- **Aktive Version wechseln** (IDE): Gradle-Task `Set active project to <version>` aus der Stonecutter-Gruppe
- Versionsabhängiger Code steht in Stonecutter-Kommentaren (`//? if >=26.2 { ... //?}`); Version-Properties in `stonecutter.properties.toml`
- 1.21.11 wird mit offiziellen Mojang-Mappings gebaut (`loom-back-compat`), 26.x ist unobfuskiert → überall dieselben Klassennamen
- Gradle läuft auf Java 25 (`gradle/gradle-daemon-jvm.properties`), kompiliert wird pro Version mit Java 21 bzw. 25
- **Tests**: `gradlew :26.3:test` (Cacheformat, PNG-Optimierer, Zip-Reader, API)
- **Testlauf im Spiel**: Env-Var `ROCKETLOAD_AUTO_RELOAD=1` → nach ~10 s Reload ohne Änderung, dann Pack-Aktivierung + Folge-Reload, danach beendet sich das Spiel

### `.rocketfuel`-Format (v1, Writer v4)

```
Header (44 B): magic "RKTFUEL\x01" | version | sourceSize | sourceMtime | indexOffset | entryCount | writerVersion
Daten:         Rohbytes aller Einträge, unkomprimiert, hintereinander (dedupliziert)
Index:         pro Eintrag: pathLen | path (UTF-8, Zip-Stil) | offset | size
```

Alles Big-Endian. Gelesen wird per positionellem `FileChannel.read` (keine Memory-Maps → keine Windows-Sperren, kein JVM-Crash beim Schließen).

## Lizenz

MIT
