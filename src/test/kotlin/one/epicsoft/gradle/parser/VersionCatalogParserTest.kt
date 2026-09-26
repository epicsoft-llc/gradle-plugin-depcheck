package one.epicsoft.gradle.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class VersionCatalogParserTest {

    @TempDir
    lateinit var dir: File

    private fun parse(content: String): VersionCatalog =
        VersionCatalogParser.parse(File(dir, "libs.versions.toml").apply { writeText(content.trimIndent()) })

    private val catalog by lazy {
        parse(
            """
            [versions]
            spring = "3.4.1"
            jackson = '2.18.3'

            [libraries]
            # string notation, module shorthand with version.ref and direct version, inline table
            commons-lang = "org.apache.commons:commons-lang3:3.17.0"
            spring-boot-starter = { module = "org.springframework.boot:spring-boot-starter", version.ref = "spring" }
            guava = { module = "com.google.guava:guava", version = "33.4.0-jre" }
            jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version.ref = "jackson" }
            dangling = { module = "com.example:dangling", version.ref = "missing" }

            [plugins]
            spring-boot = { id = "org.springframework.boot", version.ref = "spring" }
            kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.4.10" }
            """
        )
    }

    @Test
    fun `all notations are read and version refs resolved`() {
        assertEquals(
            listOf(
                LibraryEntry("org.apache.commons", "commons-lang3", "3.17.0"),
                LibraryEntry("org.springframework.boot", "spring-boot-starter", "3.4.1"),
                LibraryEntry("com.google.guava", "guava", "33.4.0-jre"),
                LibraryEntry("com.fasterxml.jackson.core", "jackson-databind", "2.18.3"),
            ),
            catalog.libraries,
        )
    }

    @Test
    fun `plugins are read with ref and direct version`() {
        assertEquals(
            listOf(PluginEntry("org.springframework.boot", "3.4.1"), PluginEntry("org.jetbrains.kotlin.jvm", "2.4.10")),
            catalog.plugins,
        )
    }

    @Test
    fun `aliases map to the accessor used in build files`() {
        assertEquals("3.4.1", catalog.libraryByAccessor["spring.boot.starter"]?.version)
        assertEquals("org.jetbrains.kotlin.jvm", catalog.pluginByAccessor["kotlin.jvm"]?.id)
    }
}
