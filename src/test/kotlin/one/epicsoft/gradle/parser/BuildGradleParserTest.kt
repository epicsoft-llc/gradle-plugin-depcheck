package one.epicsoft.gradle.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals

class BuildGradleParserTest {

    @TempDir
    lateinit var dir: File

    private fun file(content: String): File = File(dir, "build.gradle").apply { writeText(content.trimIndent()) }

    private val build by lazy {
        file(
            """
            plugins {
              id "org.springframework.boot" version "3.4.1"
              id("com.github.ben-manes.versions") version "0.52.0"
              id "java"
              alias(libs.plugins.kotlin.jvm)
            }

            def jacksonVersion = "2.18.3"

            dependencies {
              implementation "org.apache.commons:commons-lang3:3.17.0"
              testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
              implementation "com.fasterxml.jackson.core:jackson-databind:${'$'}{jacksonVersion}"
              implementation "com.example:from-properties:${'$'}fromProperties"
              implementation "com.example:unresolved:${'$'}{missing}"
              implementation libs.spring.boot.starter
              implementation(libs.guava)
              targetCompatibility = libs.versions.java.get()
            }
            """
        )
    }

    @Test
    fun `dependencies in Groovy and Kotlin notation, variables resolved, unresolvable ones skipped`() {
        val properties = Properties().apply { setProperty("fromProperties", "1.2.3") }
        assertEquals(
            listOf(
                DependencyEntry("org.apache.commons", "commons-lang3", "3.17.0"),
                DependencyEntry("org.junit.platform", "junit-platform-launcher", "1.12.2"),
                DependencyEntry("com.fasterxml.jackson.core", "jackson-databind", "2.18.3"),
                DependencyEntry("com.example", "from-properties", "1.2.3"),
            ),
            BuildGradleParser.parse(build, properties).dependencies,
        )
    }

    @Test
    fun `an unresolvable version is reported, not dropped`() {
        assertEquals(
            listOf(DependencyEntry("com.example", "unresolved", "\${missing}")),
            BuildGradleParser.parse(build, Properties().apply { setProperty("fromProperties", "1.2.3") }).unresolved,
        )
    }

    @Test
    fun `boms and platforms count, catalog versions resolve`() {
        val boms = File(dir, "boms.gradle").apply {
            writeText(
                """
                dependencyManagement {
                  imports {
                    mavenBom "org.springframework.cloud:spring-cloud-dependencies:${'$'}{libs.versions.spring.cloud.get()}"
                  }
                }
                dependencies {
                  implementation platform("com.example:groovy-bom:1.0.0")
                  implementation(enforcedPlatform("com.example:kotlin-bom:2.0.0"))
                }
                """.trimIndent()
            )
        }
        assertEquals(
            listOf(
                DependencyEntry("org.springframework.cloud", "spring-cloud-dependencies", "2025.0.0"),
                DependencyEntry("com.example", "groovy-bom", "1.0.0"),
                DependencyEntry("com.example", "kotlin-bom", "2.0.0"),
            ),
            BuildGradleParser.parse(boms, catalogVersions = mapOf("spring.cloud" to "2025.0.0")).dependencies,
        )
    }

    @Test
    fun `only plugins with a version are checked`() {
        assertEquals(
            listOf(PluginEntry("org.springframework.boot", "3.4.1"), PluginEntry("com.github.ben-manes.versions", "0.52.0")),
            BuildGradleParser.parse(build).plugins,
        )
    }

    @Test
    fun `catalog aliases are split into libraries and plugins, versions ignored`() {
        val usages = BuildGradleParser.extractCatalogAliases(build)
        assertEquals(setOf("spring.boot.starter", "guava"), usages.libraryAccessors)
        assertEquals(setOf("kotlin.jvm"), usages.pluginAccessors)
    }
}
