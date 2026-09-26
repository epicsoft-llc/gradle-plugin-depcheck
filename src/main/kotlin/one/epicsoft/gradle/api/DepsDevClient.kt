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
        internal val VERSION_SPLIT_REGEX = Regex("[.\\-]")

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

        val candidates = versions.asSequence()
            .filter { it["isRetracted"] != true }
            .mapNotNull { v ->
                @Suppress("UNCHECKED_CAST")
                (v["versionKey"] as? Map<String, Any>)?.get("version") as? String
            }
        return Versions.latest(candidates, includePreRelease, pattern)
    }
}
