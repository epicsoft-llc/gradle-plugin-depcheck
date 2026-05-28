# Changelog

## [0.0.1] - 2026-05-27

### Added
- Initial release
- Task `checkDependencyUpdates` — checks for newer versions via [deps.dev](https://deps.dev) API
- Supports `gradle/libs.versions.toml` (Version Catalog) with `version.ref` resolution
- Supports `build.gradle` / `build.gradle.kts` (Groovy + Kotlin DSL)
- Parallel HTTP requests via Java Virtual Threads
- Pre-release filter (snapshot, alpha, beta, rc, milestone)
- Publish to Nexus (`nexus.epicsoft.cloud`)
