# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
the versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Entries up to 0.2.5 were reconstructed from the Git tags and commit messages.

## [0.4.1] - 2026-09-28

### Added
- MIT license
- Sources JAR next to the plugin JAR
- CI creates a GitHub release for every tag, with the changelog entry and the JARs attached

### Changed
- README: plugin repository to declare in `settings.gradle`, links to GitLab and the GitHub mirror

## [0.4.0] - 2026-09-26

### Added
- `mavenRepositories`: coordinates deps.dev does not know — own libraries and Gradle plugins in a package registry —
  are looked up in these repositories via `maven-metadata.xml`. Maven Central stays with deps.dev and is refused
  there. Anonymous access only; a URL with credentials fails the task instead of reaching the log. Inherited by
  subprojects
- `mavenBom "g:a:v"` (Spring dependency management), `platform(…)` and `enforcedPlatform(…)` are scanned
- Versions from the catalog in a build file, `${libs.versions.<alias>.get()}`, are resolved
- Without `verbose`, a single line tells how many dependencies were not checked

### Fixed
- A dependency whose version expression cannot be resolved is reported as not checked instead of vanishing
- Catalog aliases with `_` map to the same accessor as with `-` (`groovy_core` → `libs.groovy.core`)

## [0.3.0] - 2026-09-26

### Added
- `maxVersion` patterns: a trailing `x` sets the level at which an update counts — `"2.x"` reports new minor lines only, no patches; `"x"` majors only; `"x.x"` majors and minors. An invalid pattern fails the task and names the coordinate
- Lookups that fail (deps.dev or services.gradle.org unreachable, HTTP error, unreadable response) are reported as a warning and the result is marked incomplete, instead of being dropped silently
- A warning when a `maxVersion` pattern matches no version; with `verbose`, the packages deps.dev does not know (e.g. private artifacts)
- Tests: version patterns, the deps.dev client against a local HTTP server, both parsers, root/subproject inheritance, and a TestKit build with the configuration cache and `--warning-mode=fail`
- CI publishes the test results as a JUnit report

### Changed
- The task no longer touches `Task.project` at execution time (an error from Gradle 10 on); root directory, root flag and build files are captured when the task is registered. The task works with the configuration cache
- Gradle wrapper 9.8.0, Kotlin 2.4.10 (the version embedded in Gradle 9.8.0)
- CI no longer caches Gradle downloads between pipelines — the pipeline runs rarely, and every dependency change created a new cache archive
- README entirely in English

## [0.2.5] - 2026-06-10
### Fixed
- Version comparison

## [0.2.4] - 2026-06-10
### Added
- `checkSubprojects` — root task checks only the root build
- README update

## [0.2.3] - 2026-06-10
### Fixed
- Merging of `exclude` between root and subproject

## [0.2.2] - 2026-06-10
### Fixed
- Version detection

## [0.2.1] - 2026-06-10
### Changed
- `excludeMode` / `maxVersionMode` as strings instead of an enum

## [0.2.0] - 2026-06-10
### Added
- Configuration in the root project, inherited by subprojects

## [0.1.9] - 2026-06-10
### Fixed
- Legacy timestamp versions no longer count as newest
### Changed
- Gson 2.14.0

## [0.1.7] - 2026-06-10
### Fixed
- Subprojects and the Gradle wrapper check

## [0.1.6] - 2026-06-10
### Added
- Plugin applied to subprojects automatically
- Gradle wrapper version check

## [0.1.4] - 2026-06-10
### Fixed
- Reading `libs.versions.toml`

## [0.1.3] - 2026-06-10
### Added
- `exclude` and `maxVersion`

## [0.1.2] - 2026-05-28
### Added
- Task for subprojects

## [0.1.1] - 2026-05-28
### Added
- Versions from variables in build files

## [0.1.0] - 2026-05-28
- Same code as 0.0.8

## [0.0.8] - 2026-05-28
### Added
- Labels in the output
### Fixed
- Plugin URL

## [0.0.7] - 2026-05-28
### Changed
- Configuration and pre-release check

## [0.0.6] - 2026-05-28
### Added
- Configuration options

## [0.0.5] - 2026-05-28
### Fixed
- Build files are read from each project's own directory, not only from the root

## [0.0.4] - 2026-05-28
### Fixed
- Build error, parser

## [0.0.3] - 2026-05-28
### Fixed
- Version number in CI

## [0.0.2] - 2026-05-28
### Added
- `verbose` option

## [0.0.1] - 2026-05-28
### Added
- Initial release
- Task `checkDependencyUpdates` — checks for newer versions via [deps.dev](https://deps.dev) API
- Supports `gradle/libs.versions.toml` (Version Catalog) with `version.ref` resolution
- Supports `build.gradle` / `build.gradle.kts` (Groovy + Kotlin DSL)
- Parallel HTTP requests via Java Virtual Threads
- Pre-release filter (snapshot, alpha, beta, rc, milestone)
