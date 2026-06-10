# epicsoft-gradle-plugin

Gradle plugin that checks for newer versions of your dependencies and Gradle plugins by querying the [Google deps.dev](https://deps.dev) Open Source Insights API.

Supports both `gradle/libs.versions.toml` (Version Catalog) and `build.gradle` / `build.gradle.kts`.

---

## Usage

### 1. Plugin einbinden

Plugin in `build.gradle` hinzufügen:

```groovy
plugins {
  id "one.epicsoft.deps-update" version "0.2.0"
}
```

### 2. Konfiguration (optional)

```groovy
depsUpdate {
  verbose           = true   // default: false — deps.dev URL bei jedem Eintrag anzeigen
  showAll           = true   // default: false — alle geprüften Dependencies anzeigen, nicht nur Updates
  failOnUpdates     = true   // default: false — Build fehlschlagen lassen, wenn Updates verfügbar sind
  includePreRelease = true   // default: false — RC, Alpha, Beta, Milestone als gültige Updates werten

  // Koordinaten von der Prüfung ausschließen (group:name für Libraries, Plugin-ID für Plugins)
  exclude = ["com.example:some-lib", "org.some.plugin"]

  // Versions-Obergrenze pro Koordinate — nur Updates innerhalb des Präfix werden gemeldet
  maxVersion = [
    "org.springframework.boot:spring-boot-starter": "3",   // nur 3.x.x
    "org.springframework.boot"                    : "3.2"  // nur 3.2.x
  ]

  checkGradleWrapper = true  // default: true — Gradle Wrapper Version prüfen

  // Pflichtfelder, wenn Root-Projekt UND Subprojekt denselben Parameter setzen:
  excludeMode    = CollectionInheritMode.MERGE    // MERGE = zusammenführen, OVERRIDE = nur Subprojekt
  maxVersionMode = CollectionInheritMode.OVERRIDE // MERGE = zusammenführen (Subprojekt gewinnt bei Konflikten)
}
```

| Option | Typ | Default | Beschreibung |
|---|---|---|---|
| `verbose`           | `Boolean` | `false` | Alle Dependencies anzeigen (inkl. aktueller) + deps.dev-URL bei jedem Eintrag |
| `showAll`           | `Boolean` | `false` | Alle geprüften Dependencies anzeigen, nicht nur Updates (von `verbose` impliziert) |
| `failOnUpdates`     | `Boolean` | `false` | Build schlägt fehl, wenn mindestens ein Update verfügbar ist |
| `includePreRelease` | `Boolean` | `false` | RC-, Alpha-, Beta- und Milestone-Versionen als neuere Version werten |
| `exclude`           | `List<String>` | `[]` | Koordinaten, die komplett übersprungen werden (`group:name` oder Plugin-ID) |
| `maxVersion`        | `Map<String, String>` | `{}` | Versions-Präfix als Obergrenze pro Koordinate (z.B. `"3"` = nur 3.x.x, `"3.2"` = nur 3.2.x) |
| `checkGradleWrapper` | `Boolean` | `true` | Gradle Wrapper Version über `services.gradle.org` prüfen |
| `excludeMode`       | `CollectionInheritMode` | — | **Pflicht bei Kollision**: `MERGE` = root + sub zusammenführen; `OVERRIDE` = nur Subprojekt-Liste |
| `maxVersionMode`    | `CollectionInheritMode` | — | **Pflicht bei Kollision**: `MERGE` = zusammenführen, Subprojekt gewinnt bei gleichem Key; `OVERRIDE` = nur Subprojekt-Map |

### Zentrale Konfiguration (Root → Subprojekte)

Alle skalaren Optionen (`verbose`, `showAll`, `failOnUpdates`, `includePreRelease`, `checkGradleWrapper`) werden automatisch vom Root-Projekt an Subprojekte vererbt und können dort überschrieben werden.

Für `exclude` und `maxVersion` gilt:
- Nur Root definiert → Subprojekt erbt automatisch
- Nur Subprojekt definiert → Subprojekt-Wert wird verwendet
- **Beide definieren** → `excludeMode` / `maxVersionMode` **muss** im Subprojekt gesetzt sein, sonst Build-Fehler

```groovy
// build.gradle (Root)
depsUpdate {
  failOnUpdates = true
  exclude = ["com.example:legacy-lib"]
  maxVersion = ["org.springframework.boot:spring-boot-starter": "3"]
}

// core/build.gradle
depsUpdate {
  // failOnUpdates, maxVersion werden geerbt
  exclude     = ["com.example:core-internal"]
  excludeMode = CollectionInheritMode.MERGE  // → beide Listen zusammengeführt
}
```

### 3. Task ausführen

```bash
./gradlew checkDependencyUpdates
```

### Beispielausgabe

**Standard (`verbose = false`, `showAll = false`) — nur Updates:**
```
Scanning: gradle/libs.versions.toml
Scanning: build.gradle
Scanning: core/build.gradle

Available updates (2):
  org.springframework.boot:spring-boot-starter          3.2.0  →  3.4.1
  org.springframework.boot                              3.2.0  →  3.4.1
```

**`verbose = true` — Updates + deps.dev URL:**
```
Available updates (2):
  org.springframework.boot:spring-boot-starter          3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:spring-boot-starter
  org.springframework.boot                              3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:org.springframework.boot.gradle.plugin
```

**`showAll = true` — alle Dependencies:**
```
Checked 3 dependencies:
  com.fasterxml.jackson.core:jackson-databind           2.18.3
  org.springframework.boot:spring-boot-starter          3.2.0  →  3.4.1
  org.springframework.boot                              3.2.0  →  3.4.1

2 update(s) available.
```

**`showAll = true` + `verbose = true` — alle Dependencies mit URL:**
```
Checked 3 dependencies:
  com.fasterxml.jackson.core:jackson-databind           2.18.3
    https://deps.dev/maven/com.fasterxml.jackson.core:jackson-databind
  org.springframework.boot:spring-boot-starter          3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:spring-boot-starter
  org.springframework.boot                              3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:org.springframework.boot.gradle.plugin

2 update(s) available.
```

---

## Was wird gescannt?

| Quelle | Inhalt |
|---|---|
| `gradle/libs.versions.toml` | `[libraries]` und `[plugins]` (inkl. `version.ref`-Auflösung, alle Notationen) |
| `build.gradle` / `build.gradle.kts` | `implementation`, `api`, `compileOnly`, `runtimeOnly`, `testImplementation`, `classpath` + `plugins {}`-Block |

Beide Syntaxen (Groovy + Kotlin DSL) werden erkannt.

### Verhalten bei Subprojekten

| Task | Verhalten |
|---|---|
| `./gradlew checkDependencyUpdates` | Scannt `libs.versions.toml` (alle Einträge) + `build.gradle` aller Subprojekte |
| `./gradlew :core:checkDependencyUpdates` | Scannt nur `core/build.gradle` — aus dem Version Catalog werden **nur die tatsächlich referenzierten** `libs.*`-Aliases geprüft |

Catalog-Alias-Syntax (`implementation libs.someLib`) wird in Subprojekten automatisch aufgelöst und geprüft.

---

## Wie es funktioniert

1. Der Task liest alle Dependency-Koordinaten aus den Build-Dateien.
2. Für jede Koordinate wird parallel (Java Virtual Threads) die deps.dev REST API abgefragt:
   ```
   GET https://api.deps.dev/v3/systems/MAVEN/packages/{groupId%3AartifactId}
   ```
3. Retracted und Pre-Release-Versionen (`-SNAPSHOT`, `-alpha`, `-beta`, `-RC`, `-M1` …) werden herausgefiltert.
4. Gradle-Plugins werden über ihr Maven-Marker-Artifact aufgelöst: `{pluginId}:{pluginId}.gradle.plugin`

---

## Plugin entwickeln

### Voraussetzungen

- Java 25+ (Gradle-Daemon)
- Gradle Wrapper (liegt im Repo)

> Das Plugin läuft im Gradle-Daemon (Java 25). Die eigene Anwendung kann weiterhin auf Java 17+ kompiliert werden.

### Build

```bash
./gradlew build
```

### Veröffentlichen

Läuft automatisch in der CI-Pipeline beim Setzen eines Git-Tags. Publish über GitLab Package Registry — `CI_JOB_TOKEN` wird automatisch gesetzt.

### Projektstruktur

```
src/main/kotlin/one/epicsoft/gradle/
├── DepsUpdatePlugin.kt          # Registriert Extension + Task
├── DepsUpdateExtension.kt       # Konfiguration (verbose)
├── task/CheckUpdatesTask.kt     # Task-Implementierung
├── api/DepsDevClient.kt         # deps.dev HTTP-Client
└── parser/
    ├── VersionCatalogParser.kt  # libs.versions.toml
    └── BuildGradleParser.kt     # build.gradle / build.gradle.kts
```

### Neue Version veröffentlichen

1. `version` in `gradle.properties` erhöhen
2. Git-Tag setzen: `git tag v0.2.0 && git push --tags`
3. CI-Pipeline publiziert automatisch in die GitLab Package Registry
4. In Consumer-Projekten die Version aktualisieren

---

## Technischer Stack

| | |
|---|---|
| Sprache | Kotlin 2.3.20 |
| Gradle | 9.5.1 |
| HTTP | `java.net.http.HttpClient` (kein externes Framework) |
| JSON | Gson 2.11.0 |
| Parallelität | Java Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) |

> **Hinweis:** Die Kotlin-Plugin-Version muss zur gebündelten Kotlin-Version des verwendeten Gradle-Wrappers passen. Gradle 9.5.1 bündelt Kotlin 2.3.20.
