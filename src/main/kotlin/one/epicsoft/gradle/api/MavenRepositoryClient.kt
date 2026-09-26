package one.epicsoft.gradle.api

import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Looks a coordinate up in plain Maven repositories through their `maven-metadata.xml` — for what deps.dev does not
 * know, such as artifacts in a GitLab package registry. Requests are anonymous; every repository is asked and the
 * versions are combined, so an artifact spread over two repositories is judged as a whole.
 */
class MavenRepositoryClient(
    private val repositories: List<String>,
    private val includePreRelease: Boolean = false,
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** [mavenCoord] is `group:artifact`; a Gradle plugin is asked for by its marker, `id:id.gradle.plugin`. */
    fun getLatestVersion(mavenCoord: String, pattern: VersionPattern? = null): Lookup {
        val (group, artifact) = mavenCoord.split(':', limit = 2).takeIf { it.size == 2 } ?: return Lookup.Unknown
        val found = mutableListOf<Pair<String, List<String>>>()
        val failures = mutableListOf<String>()
        repositories.forEach { repository ->
            val url = "${repository.trimEnd('/')}/${group.replace('.', '/')}/$artifact/maven-metadata.xml"
            when (val result = fetch(url)) {
                is Fetched.Versions -> found += url to result.versions
                Fetched.Missing     -> {}
                is Fetched.Error    -> failures += "${hostOf(repository)}: ${result.reason}"
            }
        }
        if (found.isEmpty()) {
            return if (failures.isEmpty()) Lookup.Unknown else Lookup.Failed(failures.joinToString("; "))
        }
        val latest = Versions.latest(found.asSequence().flatMap { it.second }, includePreRelease, pattern)
            ?: return Lookup.NoMatch
        return Lookup.Latest(latest, found.first { latest in it.second }.first)
    }

    private sealed interface Fetched {
        data class Versions(val versions: List<String>) : Fetched
        data object Missing : Fetched
        data class Error(val reason: String) : Fetched
    }

    private fun fetch(url: String): Fetched = try {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(15)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            200  -> Fetched.Versions(parseVersions(response.body()))
            404  -> Fetched.Missing
            else -> Fetched.Error("HTTP ${response.statusCode()}")
        }
    } catch (e: Exception) {
        Fetched.Error(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }

    companion object {

        /** deps.dev indexes Maven Central; asking Central as well would be a second source for the same answer. */
        private val MAVEN_CENTRAL_HOSTS = setOf("repo.maven.apache.org", "repo1.maven.org", "central.sonatype.com", "search.maven.org")

        /**
         * Only anonymous http(s) repositories other than Maven Central. Credentials in a URL would end up in the log,
         * so the messages name the host at most, never the whole entry.
         *
         * @throws IllegalArgumentException naming what is wrong with [repository]
         */
        fun requireSupported(repository: String) {
            val uri = runCatching { URI(repository) }.getOrNull()
                ?: throw IllegalArgumentException("an entry is not a valid URL.")
            if (uri.rawUserInfo != null) {
                throw IllegalArgumentException("credentials in the URL are not supported (host ${uri.host}) — only anonymously readable repositories.")
            }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
                throw IllegalArgumentException("an entry is not an http(s) URL with a host.")
            }
            if (uri.host.lowercase() in MAVEN_CENTRAL_HOSTS) {
                throw IllegalArgumentException("${uri.host} is Maven Central, which deps.dev already covers — list only repositories deps.dev does not index.")
            }
        }

        /**
         * Versions listed under `versioning/versions`. The document comes from a remote server, so the parser refuses
         * a DOCTYPE outright — no external entities, no entity expansion.
         */
        internal fun parseVersions(xml: String): List<String> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
                isXIncludeAware = false
            }
            val builder = factory.newDocumentBuilder().apply { setErrorHandler(RethrowingErrorHandler) }
            val document = builder.parse(InputSource(StringReader(xml)))
            val versions = document.getElementsByTagName("versions").item(0) as? Element ?: return emptyList()
            val entries = versions.getElementsByTagName("version")
            return (0 until entries.length).map { entries.item(it).textContent.trim() }.filter { it.isNotEmpty() }
        }

        /** Only the host — a repository URL must never reach the log with anything in front of it. */
        private fun hostOf(repository: String): String = runCatching { URI(repository).host }.getOrNull() ?: "repository"
    }

    // The default handler prints "[Fatal Error]" to stderr before throwing; the failure is reported as a lookup result.
    private object RethrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) {}
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }
}
