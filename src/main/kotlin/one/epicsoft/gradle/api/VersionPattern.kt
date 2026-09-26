package one.epicsoft.gradle.api

/**
 * A `maxVersion` value. Leading numbers cap the candidates ("2.55" = 2.55.x only); a trailing `x` sets the level
 * at which a newer version counts as an update ("2.x" = new minor lines only, patches ignored).
 */
class VersionPattern private constructor(private val prefix: List<Int>, private val depth: Int?) {

    fun matches(version: String): Boolean {
        val parts = version.trimStart('v', 'V').split(DepsDevClient.VERSION_SPLIT_REGEX)
        return prefix.indices.all { parts.getOrNull(it)?.toIntOrNull() == prefix[it] }
    }

    fun isUpdate(latest: String, current: String): Boolean =
        if (depth == null) DepsDevClient.compareVersions(latest, current) > 0
        else DepsDevClient.compareParts(DepsDevClient.numericParts(latest).take(depth), DepsDevClient.numericParts(current).take(depth)) > 0

    companion object {
        private val WILDCARDS = setOf("x", "X", "*")

        /** @throws IllegalArgumentException unless numbers come first and wildcards only at the end, e.g. "2.x.5" */
        fun parse(value: String): VersionPattern {
            val parts = value.trim().split('.')
            val firstWildcard = parts.indexOfFirst { it in WILDCARDS }
            val numbers = if (firstWildcard < 0) parts else parts.subList(0, firstWildcard)
            val wildcards = if (firstWildcard < 0) emptyList() else parts.subList(firstWildcard, parts.size)
            require(numbers.all { (it.toIntOrNull() ?: -1) >= 0 } && wildcards.all { it in WILDCARDS }) {
                "invalid version pattern '$value' — expected numbers, optionally followed by x (e.g. \"2\", \"2.x\", \"2.56.x\", \"x.x\")"
            }
            return VersionPattern(numbers.map { it.toInt() }, if (firstWildcard < 0) null else parts.size)
        }
    }
}
