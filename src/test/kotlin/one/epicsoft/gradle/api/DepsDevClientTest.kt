package one.epicsoft.gradle.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DepsDevClientTest {

    private val json = versions(
        "1.0.0", "1.1.0-rc1", "1.2.0", "1.3.0.b180725", "1.4.0-M1", "2.0.0-alpha", "20040616",
        retracted = setOf("1.9.0"),
    )

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/systems/MAVEN/packages/") { exchange ->
            val (status, body) = when (exchange.requestURI.rawPath.substringAfterLast('/')) {
                "g%3Aok"      -> 200 to json
                "g%3Aempty"   -> 200 to versions()
                "g%3Agarbage" -> 200 to "not json"
                "g%3Amissing" -> 404 to ""
                else          -> 500 to ""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun client(includePreRelease: Boolean = false) =
        DepsDevClient(includePreRelease, "http://127.0.0.1:${server.address.port}")

    @Test
    fun `stable latest skips retracted, pre-release and legacy timestamp versions`() {
        assertEquals("1.2.0", client().parseLatest(json))
    }

    @Test
    fun `includePreRelease counts pre-releases`() {
        assertEquals("2.0.0-alpha", client(includePreRelease = true).parseLatest(json))
    }

    @Test
    fun `pattern limits the candidates`() {
        assertEquals("1.0.0", client().parseLatest(json, VersionPattern.parse("1.0")))
        assertNull(client().parseLatest(json, VersionPattern.parse("1.1")))
    }

    @Test
    fun `lookup outcomes are told apart`() {
        assertEquals(Lookup.Latest("1.2.0"), client().getLatestVersion("MAVEN", "g:ok"))
        assertEquals(Lookup.NoMatch, client().getLatestVersion("MAVEN", "g:empty"))
        assertEquals(Lookup.Unknown, client().getLatestVersion("MAVEN", "g:missing"))
        assertEquals(Lookup.Failed("HTTP 500"), client().getLatestVersion("MAVEN", "g:broken"))
        assertIs<Lookup.Failed>(client().getLatestVersion("MAVEN", "g:garbage"))
    }

    @Test
    fun `an unreachable server is a failure, not a missing package`() {
        val unreachable = DepsDevClient(baseUrl = "http://127.0.0.1:1")
        assertIs<Lookup.Failed>(unreachable.getLatestVersion("MAVEN", "g:ok"))
    }

    @Test
    fun `versions compare numerically`() {
        assert(DepsDevClient.compareVersions("1.10.0", "1.9.9") > 0)
        assert(DepsDevClient.compareVersions("2.0", "2.0.0") == 0)
        assert(DepsDevClient.compareVersions("v3.1", "3.0.9") > 0)
    }

    private fun versions(vararg stable: String, retracted: Set<String> = emptySet()): String =
        (stable.map { """{"versionKey":{"version":"$it"},"isRetracted":false}""" } +
            retracted.map { """{"versionKey":{"version":"$it"},"isRetracted":true}""" })
            .joinToString(",", prefix = """{"versions":[""", postfix = "]}")
}
