package one.epicsoft.gradle.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class DepsDevClient(
    private val includePreRelease: Boolean = false,
    private val baseUrl: String = "https://api.deps.dev/v3",
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val gson: Gson = Gson()

    companion object {
        private val PRERELEASE_M_REGEX     = Regex("""-m\d+$""")
        private val PRERELEASE_RC_REGEX    = Regex("""[.\-]rc\d*""")
        private val PRERELEASE_CR_REGEX    = Regex("""[.\-]cr\d*""")
        private val PRERELEASE_BUILD_REGEX = Regex("""[.\-]b\d+""")  // e.g. 2.4.0-b180725.0427
        internal val VERSION_SPLIT_REGEX   = Regex("[.\\-]")

        fun numericParts(v: String): List<Int> =
            v.trimStart('v', 'V').split(VERSION_SPLIT_REGEX).mapNotNull { it.toIntOrNull() }

        fun compareParts(pa: List<Int>, pb: List<Int>): Int {
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }

        fun compareVersions(a: String, b: String): Int = compareParts(numericParts(a), numericParts(b))
    }

    fun getLatestVersion(system: String, packageName: String, pattern: VersionPattern? = null): Lookup {
        val encoded = URLEncoder.encode(packageName, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(
            URI.create("$baseUrl/systems/$system/packages/$encoded")
        )
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200  -> parseLatest(response.body(), pattern)?.let { Lookup.Latest(it) } ?: Lookup.NoMatch
                404  -> Lookup.Unknown
                else -> Lookup.Failed("HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            Lookup.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    internal fun parseLatest(json: String, pattern: VersionPattern? = null): String? {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = gson.fromJson(json, type)

        @Suppress("UNCHECKED_CAST")
        val versions = map["versions"] as? List<Map<String, Any>> ?: return null

        return versions
            .filter { it["isRetracted"] != true }
            .mapNotNull { v ->
                @Suppress("UNCHECKED_CAST")
                (v["versionKey"] as? Map<String, Any>)?.get("version") as? String
            }
            .filter { includePreRelease || !isPreRelease(it) }
            .filter { !isLegacyTimestamp(it) }
            .filter { v -> pattern == null || pattern.matches(v) }
            .maxWithOrNull { a, b -> compareVersions(a, b) }
    }

    private fun isLegacyTimestamp(v: String): Boolean =
        VERSION_SPLIT_REGEX.split(v).any { (it.toIntOrNull() ?: 0) > 99999 }

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
