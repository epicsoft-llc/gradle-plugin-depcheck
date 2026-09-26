package one.epicsoft.gradle.api

/** Outcome of a version lookup. Only [Latest] yields a result; the others are reported so nothing is skipped silently. */
sealed interface Lookup {

    /** [source] is where the version came from when that is not deps.dev — shown with `verbose`. */
    data class Latest(val version: String, val source: String? = null) : Lookup

    /** The source knows the package, but no stable version passes the filters and the `maxVersion` pattern. */
    data object NoMatch : Lookup

    /** HTTP 404 — the source does not know the package, e.g. a private artifact. */
    data object Unknown : Lookup

    /** Any other status, a timeout or an unreadable response — the dependency was not checked. */
    data class Failed(val reason: String) : Lookup
}
