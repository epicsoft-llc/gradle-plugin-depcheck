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

class DepsDevClient(private val includePreRelease: Boolean = false) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val gson: Gson = Gson()

    companion object {
        private val PRERELEASE_M_REGEX = Regex("""-m\d+$""")
        private val VERSION_SPLIT_REGEX = Regex("[.\\-]")
    }

    fun getLatestVersion(system: String, packageName: String): String? {
        val encoded = URLEncoder.encode(packageName, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(
            URI.create("https://api.deps.dev/v3/systems/$system/packages/$encoded")
        )
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            parseLatest(response.body())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLatest(json: String): String? {
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
            .maxWithOrNull { a, b -> compareVersions(a, b) }
    }

    private fun isPreRelease(v: String): Boolean {
        val l = v.lowercase()
        return l.contains("snapshot") ||
               l.contains("alpha") ||
               l.contains("beta") ||
               Regex("""[.\-]rc\d*""").containsMatchIn(l) ||
               PRERELEASE_M_REGEX.containsMatchIn(l)
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(VERSION_SPLIT_REGEX).mapNotNull { it.toIntOrNull() }
        val pb = b.split(VERSION_SPLIT_REGEX).mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
