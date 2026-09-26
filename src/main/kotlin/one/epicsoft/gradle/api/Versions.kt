package one.epicsoft.gradle.api

/** Picks the newest usable version. Shared by every source, so deps.dev and a Maven repository judge alike. */
object Versions {

    private val NUMERIC_START          = Regex("""^[vV]?\d""")
    private val PRERELEASE_M_REGEX     = Regex("""-m\d+$""")
    private val PRERELEASE_RC_REGEX    = Regex("""[.\-]rc\d*""")
    private val PRERELEASE_CR_REGEX    = Regex("""[.\-]cr\d*""")
    private val PRERELEASE_BUILD_REGEX = Regex("""[.\-]b\d+""")  // e.g. 2.4.0-b180725.0427

    /** Non-numeric entries such as `main` or `develop` (branch builds in a package registry) never count. */
    fun latest(candidates: Sequence<String>, includePreRelease: Boolean, pattern: VersionPattern? = null): String? =
        candidates
            .filter { NUMERIC_START.containsMatchIn(it) }
            .filter { includePreRelease || !isPreRelease(it) }
            .filter { !isLegacyTimestamp(it) }
            .filter { pattern == null || pattern.matches(it) }
            .maxWithOrNull { a, b -> DepsDevClient.compareVersions(a, b) }

    private fun isLegacyTimestamp(v: String): Boolean =
        DepsDevClient.VERSION_SPLIT_REGEX.split(v).any { (it.toIntOrNull() ?: 0) > 99999 }

    private fun isPreRelease(v: String): Boolean {
        val l = v.lowercase()
        return l.contains("snapshot") ||
               l.contains("alpha") ||
               l.contains("beta") ||
               PRERELEASE_RC_REGEX.containsMatchIn(l) ||
               PRERELEASE_CR_REGEX.containsMatchIn(l) ||
               PRERELEASE_M_REGEX.containsMatchIn(l) ||
               PRERELEASE_BUILD_REGEX.containsMatchIn(l)
    }
}
