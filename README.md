# epicsoft-gradle-plugin

Gradle plugin that checks for newer versions of your dependencies and Gradle plugins by querying the [Google deps.dev](https://deps.dev) Open Source Insights API.

Supports both `gradle/libs.versions.toml` (Version Catalog) and `build.gradle` / `build.gradle.kts`.

---

## Usage

### 1. Plugin einbinden

Plugin in `build.gradle` hinzufügen:

```groovy
plugins {
  id "one.epicsoft.deps-update" version "0.0.1"
}
```

### 2. Task ausführen

```bash
./gradlew checkDependencyUpdates
```

### Beispielausgabe

```
Scanning: gradle/libs.versions.toml
Scanning: build.gradle
Scanning: core/build.gradle

Available updates (3):
  [dep]     org.springframework.boot:spring-boot-starter  3.2.0  →  3.4.1
  [dep]     com.fasterxml.jackson.core:jackson-databind   2.15.0 →  2.18.3
  [plugin]  org.springframework.boot  3.2.0  →  3.4.1
```

---

## Was wird gescannt?

| Quelle | Inhalt |
|---|---|
| `gradle/libs.versions.toml` | `[libraries]` und `[plugins]` (inkl. `version.ref`-Auflösung) |
| `build.gradle` / `build.gradle.kts` | `implementation`, `api`, `compileOnly`, `runtimeOnly`, `testImplementation`, `classpath` + `plugins {}`-Block |

Gescannt werden Root-Projekt und alle Subprojekte. Beide Syntaxen (Groovy + Kotlin DSL) werden erkannt.

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
├── DepsUpdatePlugin.kt          # Registriert den Task
├── task/CheckUpdatesTask.kt     # Task-Implementierung
├── api/DepsDevClient.kt         # deps.dev HTTP-Client
└── parser/
    ├── VersionCatalogParser.kt  # libs.versions.toml
    └── BuildGradleParser.kt     # build.gradle / build.gradle.kts
```

### Neue Version veröffentlichen

1. `version` in `gradle.properties` erhöhen
2. Git-Tag setzen: `git tag v0.0.2 && git push --tags`
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
