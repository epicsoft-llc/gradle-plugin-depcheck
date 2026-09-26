package one.epicsoft.gradle.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionSourcesTest {

    private lateinit var server: HttpServer
    private val repositoryRequests = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/depsdev/") { exchange ->
            val known = exchange.requestURI.rawPath.endsWith("com.example%3Apublic")
            val body = if (known) """{"versions":[{"versionKey":{"version":"4.0.0"}}]}""" else ""
            respond(exchange, if (known) 200 else 404, body)
        }
        server.createContext("/maven/") { exchange ->
            repositoryRequests += exchange.requestURI.path
            val known = exchange.requestURI.path == "/maven/com/example/private/maven-metadata.xml"
            respond(exchange, if (known) 200 else 404, if (known) "<metadata><versioning><versions><version>1.5.0</version></versions></versioning></metadata>" else "")
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun sources(withRepository: Boolean = true) = VersionSources(
        DepsDevClient(baseUrl = "$base/depsdev"),
        if (withRepository) MavenRepositoryClient(listOf("$base/maven")) else null,
    )

    @Test
    fun `deps dev answers first, the repositories are not asked`() {
        assertEquals(Lookup.Latest("4.0.0"), sources().latest("com.example:public", null))
        assertTrue(repositoryRequests.isEmpty(), repositoryRequests.toString())
    }

    @Test
    fun `what deps dev does not know goes to the repositories`() {
        assertEquals(
            Lookup.Latest("1.5.0", "$base/maven/com/example/private/maven-metadata.xml"),
            sources().latest("com.example:private", null),
        )
    }

    @Test
    fun `without repositories unknown stays unknown`() {
        assertEquals(Lookup.Unknown, sources(withRepository = false).latest("com.example:private", null))
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
