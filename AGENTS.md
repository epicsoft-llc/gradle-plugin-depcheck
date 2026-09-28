# AGENTS.md — DepCheck Gradle Plugin

Guidance for coding agents working in this repository. User documentation lives in [`README.md`](README.md).

Gradle plugin that checks for newer versions of dependencies and Gradle plugins by querying the [Google deps.dev](https://deps.dev) Open Source Insights API.

## Coordinates

| | |
|---|---|
| **Plugin ID** | `one.epicsoft.deps-update` |
| **Group** | `one.epicsoft` |
| **Artifact** | `epicsoft-gradle-plugin` — the published coordinate, do not rename `rootProject.name` |
| **Version** | see `gradle.properties` → `version` |
| **Main class** | `one.epicsoft.gradle.DepsUpdatePlugin` |
| **Task** | `checkDependencyUpdates` |
| **Repository** | developed on `gitlab.com/epicsoft-networks/gradle-plugin-depcheck`, mirrored to `github.com/epicsoft-llc/gradle-plugin-depcheck` |

## Rules

- **Public repository, MIT.** No internals in any file: no internal hosts or proxies, no credentials, no names of
  projects that consume the plugin, no infrastructure details. Keep examples neutral (`com.example:…` or well-known
  public libraries). Before finishing a change, search every published file (`git ls-files` plus new files) for such
  terms.
- **Everything in English** — code, comments, README, CHANGELOG, error messages, commit messages.
- **Do not name the AI tool** in files, commit messages or trailers, or merge request texts. The only exception is
  `.gitignore`, which keeps tool-specific local files out of the repository.
- **Never commit, push, merge, rebase or tag** — changes stay in the working tree. Proposed commit messages follow
  Conventional Commits (`feat:`, `fix:`, `ci:`, `docs:`, `refactor:`, `revert:`).
- **Only a human suppresses warnings.** No `@Suppress`, `// noinspection` or `NOSONAR` — fix the cause or report the
  warning. Test code is the exception.
- **Always update `README.md`** when bumping the version in `gradle.properties` (all occurrences).
- **Keep this file current** — update it after every architectural change, new parameter, CI change or new rule.

## Commands

```bash
./gradlew build              # Compile + validate plugin + run tests (src/test, JUnit)
./gradlew checkDependencyUpdates  # Only works when consumed by another project
```

Publishing runs via CI only — push a Git tag to trigger the `publish` job.

## Architecture

```
src/main/kotlin/one/epicsoft/gradle/
├── DepsUpdatePlugin.kt          # Plugin entry point — registers extension + task
├── DepsUpdateExtension.kt       # Extension: depsUpdate { verbose = true; showAll = true }
├── task/
│   └── CheckUpdatesTask.kt      # @DisableCachingByDefault — parallel HTTP via virtual threads
├── api/
│   ├── DepsDevClient.kt         # HTTP client for api.deps.dev/v3 (base URL overridable for tests)
│   ├── MavenRepositoryClient.kt # maven-metadata.xml in `mavenRepositories` — for what deps.dev does not know
│   ├── VersionSources.kt        # deps.dev first, on Unknown the repositories
│   ├── Versions.kt              # picks the newest usable version — shared filters (pre-release, legacy, non-numeric, pattern)
│   ├── Lookup.kt                # lookup outcome: Latest (+ source) / NoMatch / Unknown (404) / Failed
│   └── VersionPattern.kt        # maxVersion value: numeric cap + optional trailing x (update level)
└── parser/
    ├── VersionCatalogParser.kt  # Parses gradle/libs.versions.toml
    └── BuildGradleParser.kt     # Parses build.gradle / build.gradle.kts (Groovy + Kotlin DSL)
```

### How it works

1. **`CheckUpdatesTask`** scans the consuming project's root for:
   - `gradle/libs.versions.toml` — parsed via `VersionCatalogParser` (always; for subprojects only aliases actually referenced via `libs.*` in the build file)
   - `build.gradle` / `build.gradle.kts` — root task scans all subprojects, subproject task scans only its own build file
2. For every library (`group:name`) and plugin (`pluginId:pluginId.gradle.plugin`) a virtual-thread job is submitted to query `VersionSources`: deps.dev, and on `Unknown` the configured `mavenRepositories` (all asked, versions combined, anonymous only — a URL with credentials fails the task before any request).
3. **`DepsDevClient`** calls `GET https://api.deps.dev/v3/systems/MAVEN/packages/{encoded}`, filters out retracted and pre-release versions, and returns the highest stable version matching the coordinate's `maxVersion` pattern (`VersionPattern`). Whether that version counts as an update is decided by the same pattern: without a trailing `x` any newer version counts, with it only a change up to the `x` position.
   It returns a `Lookup`: `Latest`, `NoMatch` (no stable version passes filters/pattern), `Unknown` (HTTP 404) or `Failed` (other status, timeout, unreadable body).
4. Results are collected, sorted, and printed via `logger.lifecycle`. **Nothing is dropped silently:** `Failed` (also for services.gradle.org) → warning "result is incomplete"; `NoMatch` with a pattern → warning; `Unknown` / `NoMatch` without a pattern / a version expression the parser cannot resolve → one line "N not checked", listed with `verbose`. Failed lookups do not fail the build.

### Configuration cache / Gradle 10

The task must **never touch `Task.project` at execution time** — deprecated in Gradle 9, an error from Gradle 10 on. Everything it needs from the project (`rootDirectory`, `runsInRootProject`, `buildFiles`) is captured in `DepsUpdatePlugin.registerTask`. `CheckUpdatesTaskFunctionalTest` (TestKit) runs the task twice with `--configuration-cache --warning-mode=fail` and fails on any such access.

### deps.dev API

- Base URL: `https://api.deps.dev/v3`
- System: `MAVEN` (URL path segment, uppercase)
- Package name: `groupId:artifactId` — URL-encoded (`%3A`)
- Gradle plugin lookup: `{pluginId}:{pluginId}.gradle.plugin` (standard Maven marker artifact)
- Pre-release filter: skips versions containing `snapshot`, `alpha`, `beta`, `-rc`, `-mN` (in `Versions.kt`, shared with the repository lookup)

### Maven repositories (`mavenRepositories`)

- `GET {repository}/{group with / for .}/{artifact}/maven-metadata.xml`, versions from `versioning/versions`
- **Never Maven Central** — deps.dev covers it. `MavenRepositoryClient.requireSupported` refuses its hosts, credentials in the URL and anything but http(s); the task turns that into a `GradleException` before the first request
- XML parsed with DOCTYPE disallowed and secure processing — the document comes from a remote server
- 404 everywhere → `Unknown`; any other error without a hit → `Failed` naming only the host, never the URL
- GitLab package registries of public projects serve `maven-metadata.xml` anonymously, also for plugin markers; they may list branch builds (`main`, `develop`) as versions — `Versions.latest` drops non-numeric entries

### Parsing rules

**`libs.versions.toml`** — supports:
- `[versions]` aliases referenced via `version.ref`
- `[libraries]` in all three notations:
  - string form: `"group:name:version"`
  - module shorthand: `{ module = "group:name", version.ref = "..." }` ← most common in modern catalogs
  - inline table: `{ group = "...", name = "...", version.ref = "..." }`
- `[plugins]` inline-table form (`{ id = "...", version.ref = "..." }`)
- Alias names are normalized to Gradle accessor form (`-` and `_` → `.`) for subproject lookup; `[versions]` too, for `libs.versions.<accessor>` in build files

**`build.gradle` / `build.gradle.kts`** — regex-based, supports:
- Dependency configs: `implementation`, `api`, `compileOnly`, `runtimeOnly`, `testImplementation`, `testRuntimeOnly`, `testCompileOnly`, `testAnnotationProcessor`, `annotationProcessor`, `developmentOnly`, `classpath`, plus `mavenBom`; the coordinate may be wrapped in `platform(…)` / `enforcedPlatform(…)`
- Versions: literal, `$var` / `${var}` from `def`/`val`/`ext.` in the same file or the root `gradle.properties`, or `${libs.versions.<accessor>.get()}` from the catalog; anything else lands in `unresolved` and is reported as not checked
- Both Groovy (`implementation "g:a:v"`) and Kotlin DSL (`implementation("g:a:v")`) syntax
- Plugin blocks: `id "x" version "y"` and `id("x") version "y"`
- Catalog alias extraction: detects all `libs.*` references and classifies as library or plugin accessor

## Build toolchain

| | |
|---|---|
| **Gradle** | 9.8.0 (`services.gradle.org`) |
| **Kotlin** | 2.4.10 — must match Gradle's bundled Kotlin version |
| **Java toolchain** | 25 — Gradle daemon must run on Java 25+; consumer projects may target Java 17+ |
| **Gson** | 2.14.0 — only runtime dependency |
| **Tests** | JUnit 6 (`junit-bom`) + `kotlin-test`; TestKit via `java-gradle-plugin` |
| **CI Image** | `epicsoft/openjdk:jdk25`; `epicsoft/ci:latest` for the GitHub release |

> **Important:** The Kotlin plugin version (`org.jetbrains.kotlin.jvm`) **must** match the Kotlin version bundled with the Gradle wrapper in use — read it from `./gradlew --version` ("Kotlin:"), not from the newest Kotlin release. Gradle 9.8.0 bundles Kotlin 2.4.10. Mismatches cause `incompatible version of Kotlin` compiler errors.

## Repositories

| Scope | Repository |
|---|---|
| Plugin resolution | Gradle Plugin Portal |
| Dependency resolution | Maven Central |
| Publish target | GitLab Package Registry (`CI_JOB_TOKEN`), project id `82634030` |

## CI/CD

Pipeline: `.gitlab-ci.yml`

| Job | Trigger | Action |
|---|---|---|
| `build` | every push | `./gradlew build` (incl. tests), JAR + sources JAR as artifact (1 week), JUnit report — kept even when the build fails |
| `publish` | Git tag | `./gradlew publish` → GitLab Package Registry |
| `github-release` | Git tag, after `publish` | image `epicsoft/ci:latest`; waits for the tag on GitHub, creates the release from the `CHANGELOG.md` entry, attaches the JARs |

**GitHub mirror:** the repository reaches GitHub through a GitLab push mirror. `github-release` needs the CI variable `GITHUB_TOKEN` (masked, protected) — protected, so the tags must be protected (`*.*.*`) or the job sees no token. The tag needs a `CHANGELOG.md` entry, otherwise the job fails.

**No CI cache, on purpose** (since 0.3.0): the pipeline runs rarely, and a cache keyed on the dependency files created a new archive with every dependency change. Gradle and dependencies come straight from services.gradle.org, the Gradle Plugin Portal and Maven Central — **never route them through a proxy**.

## Adding / changing behaviour

- **New dependency config keyword** (e.g. `annotationProcessor`): add to the regex in `BuildGradleParser.kt`
- **New package ecosystem** (e.g. npm): add a new system constant and a separate parser; pass system `NPM` to `DepsDevClient`
- **Version comparison logic**: in `DepsDevClient.compareVersions()` — numeric segment comparison
- **`maxVersion` patterns**: parsing, cap and update level in `VersionPattern.kt`; every rule change gets a case in `src/test/.../VersionPatternTest.kt`
- **Pre-release detection**: in `Versions.isPreRelease()` — extend the list there; add the case to `DepsDevClientTest`
- **Repository lookup**: `MavenRepositoryClient`; cases in `MavenRepositoryClientTest` (local HTTP servers), the chain in `VersionSourcesTest`
- **Parser changes**: add the notation to `VersionCatalogParserTest` / `BuildGradleParserTest`
- **New task input**: never read it from `project` inside `@TaskAction` — add a task property and set it in `registerTask`
- **New version**: see the release checklist

## Consuming projects

Options, defaults and examples are documented in [`README.md`](README.md). Points that matter when changing code:

- Scalar options (`verbose`, `showAll`, `failOnUpdates`, `includePreRelease`, `checkGradleWrapper`, `checkSubprojects`) and `mavenRepositories` are inherited from root as conventions — a subproject can override them by setting the value explicitly.
- For `exclude` and `maxVersion`: only root defines it → subproject inherits; only subproject defines it → subproject value; both define it → `excludeMode` / `maxVersionMode` **must** be set in the subproject, otherwise a `GradleException` is thrown at task execution time.
- The consumer's `settings.gradle` needs the GitLab Package Registry as plugin repository.

## Release checklist

- Bump `version` in `gradle.properties`
- Update version in `README.md` (all occurrences — plugin block + tag command)
- `CHANGELOG.md`: rename `## [Unreleased]` to `## [X.Y.Z] - <date>` — `github-release` takes the release notes from exactly that heading
- Push a Git tag **without `v`** (`0.3.0`, like all existing tags) to trigger the CI publish job — the tag, not `gradle.properties`, sets the published version (`-Pversion=${CI_COMMIT_TAG#v}`)
