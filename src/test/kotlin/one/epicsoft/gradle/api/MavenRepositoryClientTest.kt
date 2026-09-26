package one.epicsoft.gradle.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MavenRepositoryClientTest {

    private lateinit var first: HttpServer
    private lateinit var second: HttpServer

    @BeforeEach
    fun start() {
        first = stub(
            "com/example/lib" to (200 to metadata("main", "develop", "1.0.0", "1.1.0", "2.0.0-rc1")),
            "com/example/split" to (200 to metadata("1.0.0")),
            "com/example/broken" to (500 to ""),
            "one/example/tool/one.example.tool.gradle.plugin" to (200 to metadata("0.2.0", "0.3.0")),
        )
        second = stub(
            "com/example/split" to (200 to metadata("1.2.0")),
        )
    }

    @AfterEach
    fun stop() {
        first.stop(0)
        second.stop(0)
    }

    private fun HttpServer.repository() = "http://127.0.0.1:${address.port}/maven/"

    private fun client(includePreRelease: Boolean = false) =
        MavenRepositoryClient(listOf(first.repository(), second.repository()), includePreRelease)

    @Test
    fun `newest stable version wins, branch builds and pre-releases are skipped`() {
        assertEquals(
            Lookup.Latest("1.1.0", "${first.repository()}com/example/lib/maven-metadata.xml"),
            client().getLatestVersion("com.example:lib"),
        )
    }

    @Test
    fun `versions of all repositories are combined`() {
        assertEquals(
            Lookup.Latest("1.2.0", "${second.repository()}com/example/split/maven-metadata.xml"),
            client().getLatestVersion("com.example:split"),
        )
    }

    @Test
    fun `a Gradle plugin is looked up by its marker`() {
        assertEquals("0.3.0", (client().getLatestVersion("one.example.tool:one.example.tool.gradle.plugin") as Lookup.Latest).version)
    }

    @Test
    fun `pattern and pre-release option apply as with deps dev`() {
        assertEquals("2.0.0-rc1", (client(includePreRelease = true).getLatestVersion("com.example:lib") as Lookup.Latest).version)
        assertEquals("1.0.0", (client().getLatestVersion("com.example:lib", VersionPattern.parse("1.0")) as Lookup.Latest).version)
        assertEquals(Lookup.NoMatch, client().getLatestVersion("com.example:lib", VersionPattern.parse("3")))
    }

    @Test
    fun `unknown everywhere is Unknown, an error without a hit is a failure naming only the host`() {
        assertEquals(Lookup.Unknown, client().getLatestVersion("com.example:absent"))
        val failed = assertIs<Lookup.Failed>(client().getLatestVersion("com.example:broken"))
        assertEquals("127.0.0.1: HTTP 500", failed.reason)
    }

    @Test
    fun `Maven Central, credentials and non-http URLs are refused`() {
        MavenRepositoryClient.requireSupported("https://gitlab.example.com/api/v4/projects/1/packages/maven")
        listOf("https://repo.maven.apache.org/maven2", "https://REPO1.maven.org/maven2", "https://central.sonatype.com/repository/maven-central").forEach {
            assertContains(assertFails { MavenRepositoryClient.requireSupported(it) }.message.orEmpty(), "deps.dev already covers")
        }
        val credentials = assertFails { MavenRepositoryClient.requireSupported("https://user:s3cr3t@repo.example.com/maven") }
        assertFalse(credentials.message.orEmpty().contains("s3cr3t"))
        assertFails { MavenRepositoryClient.requireSupported("file:///tmp/repo") }
    }

    @Test
    fun `a document with a DOCTYPE is refused`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/passwd">]><metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>"""
        val error = assertFails { MavenRepositoryClient.parseVersions(xxe) }
        assertContains(error.message.orEmpty(), "DOCTYPE")
    }

    private fun stub(vararg paths: Pair<String, Pair<Int, String>>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            val byPath = paths.associate { (path, answer) -> "/maven/$path/maven-metadata.xml" to answer }
            createContext("/") { exchange ->
                val (status, body) = byPath[exchange.requestURI.path] ?: (404 to "")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun metadata(vararg versions: String): String =
        versions.joinToString("", prefix = "<metadata><versioning><versions>", postfix = "</versions></versioning></metadata>") {
            "<version>$it</version>"
        }
}
