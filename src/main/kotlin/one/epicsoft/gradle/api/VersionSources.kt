package one.epicsoft.gradle.api

/** deps.dev first; what it does not know goes to the configured Maven repositories, if there are any. */
class VersionSources(
    private val depsDev: DepsDevClient,
    private val repositories: MavenRepositoryClient?,
) {

    fun latest(mavenCoord: String, pattern: VersionPattern?): Lookup {
        val lookup = depsDev.getLatestVersion("MAVEN", mavenCoord, pattern)
        return if (lookup == Lookup.Unknown && repositories != null) repositories.getLatestVersion(mavenCoord, pattern) else lookup
    }
}
