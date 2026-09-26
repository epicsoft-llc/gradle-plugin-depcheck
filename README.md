# epicsoft-gradle-plugin

Gradle plugin that checks for newer versions of your dependencies and Gradle plugins by querying the [Google deps.dev](https://deps.dev) Open Source Insights API.

Supports both `gradle/libs.versions.toml` (Version Catalog) and `build.gradle` / `build.gradle.kts`.

---

## Usage

### 1. Apply the plugin

Add the plugin to `build.gradle`:

```groovy
plugins {
  id "one.epicsoft.deps-update" version "0.3.0"
}
```

### 2. Configuration (optional)

```groovy
depsUpdate {
  verbose           = true   // default: false — show the deps.dev URL for every entry
  showAll           = true   // default: false — show all checked dependencies, not only updates
  failOnUpdates     = true   // default: false — fail the build when updates are available
  includePreRelease = true   // default: false — count RC, Alpha, Beta, Milestone as valid updates

  // Exclude coordinates from the check (group:name for libraries, plugin ID for plugins)
  exclude = ["com.example:some-lib", "org.some.plugin"]

  // Version pattern per coordinate: numbers cap the candidates, a trailing "x" sets the level at which an update counts
  maxVersion = [
    "org.springframework.boot:spring-boot-starter": "3",    // 3.x.x only, every newer 3.x.y is reported
    "org.springframework.boot"                    : "3.2",  // 3.2.x only
    "software.amazon.awssdk:s3"                   : "2.x"   // 2.x.x, new minor lines only — no patches
  ]

  checkGradleWrapper = true  // default: true — check the Gradle Wrapper version
  checkSubprojects   = false // default: true — check the root build only, ignore subprojects

  // Required when the root project AND a subproject set the same parameter:
  excludeMode    = "MERGE"    // "MERGE" = combine, "OVERRIDE" = subproject only
  maxVersionMode = "OVERRIDE" // "MERGE" = combine (subproject wins on conflicts)
}
```

| Option | Type | Default | Description |
|---|---|---|---|
| `verbose`           | `Boolean` | `false` | Show all dependencies (incl. current ones) + the deps.dev URL for every entry |
| `showAll`           | `Boolean` | `false` | Show all checked dependencies, not only updates (implied by `verbose`) |
| `failOnUpdates`     | `Boolean` | `false` | The build fails when at least one update is available |
| `includePreRelease` | `Boolean` | `false` | Count RC, Alpha, Beta and Milestone versions as newer versions |
| `exclude`           | `List<String>` | `[]` | Coordinates skipped entirely (`group:name` or plugin ID) |
| `maxVersion`        | `Map<String, String>` | `{}` | Version pattern per coordinate: numbers = cap, trailing `x` = level at which an update counts (table below) |
| `checkGradleWrapper` | `Boolean` | `true` | Check the Gradle Wrapper version via `services.gradle.org` |
| `checkSubprojects`  | `Boolean` | `true` | `false` = the root task checks only the root `build.gradle` + the catalog entries referenced there |
| `excludeMode`       | `String` | — | **Required on collision**: `"MERGE"` = combine root + sub; `"OVERRIDE"` = subproject list only |
| `maxVersionMode`    | `String` | — | **Required on collision**: `"MERGE"` = combine, subproject wins on the same key; `"OVERRIDE"` = subproject map only |

### Version patterns in `maxVersion`

Leading numbers cap which versions are considered. A trailing `x` (also `X` or `*`) sets the level at which a newer version counts as an update — anything after it is ignored. `x` may only come last; `"2.x.5"` fails the task with an error message.

| Value | Considered | Reported |
|---|---|---|
| `"3"` | 3.x.x | every newer 3.x.y |
| `"2.55"` | 2.55.x | every newer 2.55.y |
| `"2.x"` | 2.x.x | a new minor line only (2.56.0), no patches |
| `"2.56.x"` | 2.56.x | every newer 2.56.y — same as `"2.56"` |
| `"x"` | all | a new major version only |
| `"x.x"` | all | new major and minor versions, no patches |

### Central configuration (root → subprojects)

All scalar options (`verbose`, `showAll`, `failOnUpdates`, `includePreRelease`, `checkGradleWrapper`) are inherited automatically from the root project by the subprojects and can be overridden there.

For `exclude` and `maxVersion`:
- Only the root defines it → the subproject inherits it automatically
- Only the subproject defines it → the subproject value is used
- **Both define it** → `excludeMode` / `maxVersionMode` **must** be set in the subproject, otherwise the build fails

```groovy
// build.gradle (root) — OUTSIDE of subprojects {}
depsUpdate {
  failOnUpdates = true
  exclude = ["com.example:legacy-lib"]
  maxVersion = ["org.springframework.boot:spring-boot-starter": "3"]
}

// core/build.gradle
depsUpdate {
  // failOnUpdates, maxVersion are inherited
  exclude     = ["com.example:core-internal"]
  excludeMode = "MERGE"  // → both lists combined
}
```

> **Caution:** `depsUpdate {}` in the root must **not** be placed inside `subprojects {}`. A `subprojects { depsUpdate { exclude = [...] } }` configures the extension of every subproject directly — when the subproject then runs its own `depsUpdate { exclude = [...] }`, Gradle overwrites the value completely. The root `depsUpdate {}` stays empty and inheritance does not apply.
>
> **Correct:**
> ```groovy
> // Root build.gradle
> depsUpdate { exclude = ["com.example:shared-exclude"] }  // ← at root level
>
> subprojects {
>   apply plugin: "one.epicsoft.deps-update"  // the plugin is already applied via cascade
>   // no depsUpdate {} here
> }
> ```

### 3. Run the task

```bash
./gradlew checkDependencyUpdates
```

### Example output

**Default (`verbose = false`, `showAll = false`) — updates only:**
```
Scanning: gradle/libs.versions.toml
Scanning: build.gradle
Scanning: core/build.gradle

Available updates (2):
  [library]  org.springframework.boot:spring-boot-starter             3.2.0  →  3.4.1
  [plugin ]  org.springframework.boot                                 3.2.0  →  3.4.1
```

**`verbose = true` — updates + deps.dev URL:**
```
Available updates (2):
  [library]  org.springframework.boot:spring-boot-starter             3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:spring-boot-starter
  [plugin ]  org.springframework.boot                                 3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:org.springframework.boot.gradle.plugin
```

**`showAll = true` — all dependencies:**
```
Checked 3 dependencies:
  [library]  com.fasterxml.jackson.core:jackson-databind              2.18.3
  [library]  org.springframework.boot:spring-boot-starter             3.2.0  →  3.4.1
  [plugin ]  org.springframework.boot                                 3.2.0  →  3.4.1

2 update(s) available.
```

**`showAll = true` + `verbose = true` — all dependencies with URL:**
```
Checked 3 dependencies:
  [library]  com.fasterxml.jackson.core:jackson-databind              2.18.3
    https://deps.dev/maven/com.fasterxml.jackson.core:jackson-databind
  [library]  org.springframework.boot:spring-boot-starter             3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:spring-boot-starter
  [plugin ]  org.springframework.boot                                 3.2.0  →  3.4.1
    https://deps.dev/maven/org.springframework.boot:org.springframework.boot.gradle.plugin

2 update(s) available.
```

---

## What is scanned?

| Source | Content |
|---|---|
| `gradle/libs.versions.toml` | `[libraries]` and `[plugins]` (incl. `version.ref` resolution, all notations) |
| `build.gradle` / `build.gradle.kts` | `implementation`, `api`, `compileOnly`, `runtimeOnly`, `testImplementation`, `testRuntimeOnly`, `testCompileOnly`, `testAnnotationProcessor`, `annotationProcessor`, `developmentOnly`, `classpath` + `plugins {}` block |

Both syntaxes (Groovy + Kotlin DSL) are recognized.

### Behaviour with subprojects

| Task | Behaviour |
|---|---|
| `./gradlew checkDependencyUpdates` | Runs the task in **all** projects (root + all subprojects) |
| `./gradlew :checkDependencyUpdates` | Runs **only** the root task — scans `libs.versions.toml` (all entries) + the `build.gradle` of all subprojects |
| `./gradlew :checkDependencyUpdates` (`checkSubprojects = false`) | Runs only the root task and scans only the root `build.gradle` + the catalog entries referenced there |
| `./gradlew :core:checkDependencyUpdates` | Scans only `core/build.gradle` — from the version catalog **only the `libs.*` aliases actually referenced** are checked |

> **Note:** `./gradlew checkDependencyUpdates` (without `:`) is standard Gradle behaviour — Gradle runs the task in every project that registered it. For root only, always use `./gradlew :checkDependencyUpdates`.

Catalog alias syntax (`implementation libs.someLib`) is resolved and checked automatically in subprojects.

---

## How it works

1. The task reads all dependency coordinates from the build files.
2. For every coordinate the deps.dev REST API is queried in parallel (Java Virtual Threads):
   ```
   GET https://api.deps.dev/v3/systems/MAVEN/packages/{groupId%3AartifactId}
   ```
3. Retracted and pre-release versions (`-SNAPSHOT`, `-alpha`, `-beta`, `-RC`, `-M1` …) are filtered out.
4. Gradle plugins are resolved via their Maven marker artifact: `{pluginId}:{pluginId}.gradle.plugin`

---

## Developing the plugin

### Requirements

- Java 25+ (Gradle daemon)
- Gradle Wrapper (in the repo)

> The plugin runs in the Gradle daemon (Java 25). Your own application can still be compiled for Java 17+.

### Build

```bash
./gradlew build    # compiles, validates the plugin and runs the tests (src/test)
```

### Publishing

Runs automatically in the CI pipeline when a Git tag is pushed. Publishes to the GitLab Package Registry — `CI_JOB_TOKEN` is set automatically.

### Project structure

```
src/main/kotlin/one/epicsoft/gradle/
├── DepsUpdatePlugin.kt          # Registers extension + task
├── DepsUpdateExtension.kt       # Configuration (verbose, exclude, maxVersion, …)
├── task/CheckUpdatesTask.kt     # Task implementation
├── api/DepsDevClient.kt         # deps.dev HTTP client
├── api/VersionPattern.kt        # maxVersion pattern (cap + update level)
└── parser/
    ├── VersionCatalogParser.kt  # libs.versions.toml
    └── BuildGradleParser.kt     # build.gradle / build.gradle.kts
```

### Releasing a new version

1. Bump `version` in `gradle.properties`
2. Set a Git tag: `git tag v0.3.0 && git push --tags`
3. The CI pipeline publishes to the GitLab Package Registry automatically
4. Update the version in the consuming projects

---

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.3.20 |
| Gradle | 9.5.1 |
| HTTP | `java.net.http.HttpClient` (no external framework) |
| JSON | Gson 2.14.0 |
| Tests | JUnit 6 + `kotlin-test` |
| Concurrency | Java Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) |

> **Note:** The Kotlin plugin version must match the Kotlin version bundled with the Gradle wrapper in use. Gradle 9.5.1 bundles Kotlin 2.3.20.
