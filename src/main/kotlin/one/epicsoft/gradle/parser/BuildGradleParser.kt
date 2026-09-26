package one.epicsoft.gradle.parser

import java.io.File
import java.util.Properties

data class DependencyEntry(val group: String, val name: String, val version: String)
/** [unresolved] holds entries whose version expression the parser cannot resolve — reported, never dropped silently. */
data class BuildGradleEntries(val dependencies: List<DependencyEntry>, val plugins: List<PluginEntry>, val unresolved: List<DependencyEntry> = emptyList())
data class CatalogAliasUsages(val libraryAccessors: Set<String>, val pluginAccessors: Set<String>)

object BuildGradleParser {

    private val catalogAliasRegex = Regex("""libs\.([a-zA-Z][a-zA-Z0-9.]*)""")

    // Also a BOM: `mavenBom "g:a:v"` (Spring dependency management) and `platform(...)` / `enforcedPlatform(...)`.
    // The version may hold a catalog expression such as ${libs.versions.x.get()}, hence parentheses are allowed there.
    private val depRegex = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly|testAnnotationProcessor|annotationProcessor|developmentOnly|classpath|mavenBom)\s*\(?\s*(?:(?:enforcedPlatform|platform)\s*\(\s*)?["']([^"':()]+):([^"'@:()]+):([^"'@:]+)["']"""
    )

    private val catalogVersionRegex = Regex("""^libs\.versions\.([A-Za-z][\w.]*?)(?:\.get\(\))?$""")

    private val pluginRegex = Regex(
        """id\s*\(?\s*["']([^"'()]+)["']\s*\)?\s+version\s+["']([^"']+)["']"""
    )

    private val varRegex = Regex("""\$\{([^}]+)}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    // def x = "v" / final def x = "v" / val x = "v" / ext.x = "v"
    private val localVarRegex = Regex(
        """(?:(?:final\s+)?(?:def|val)\s+|ext\.)(\w+)\s*=\s*["']([^"']+)["']"""
    )

    fun extractCatalogAliases(file: File): CatalogAliasUsages {
        val libraryAccessors = mutableSetOf<String>()
        val pluginAccessors = mutableSetOf<String>()
        catalogAliasRegex.findAll(file.readText()).forEach { match ->
            val accessor = match.groupValues[1].trimEnd('.')
            when {
                accessor.startsWith("plugins.") -> pluginAccessors += accessor.removePrefix("plugins.")
                accessor.startsWith("versions.") -> {}
                else -> libraryAccessors += accessor
            }
        }
        return CatalogAliasUsages(libraryAccessors, pluginAccessors)
    }

    /** [catalogVersions] maps version accessors (`spring.cloud` for `spring-cloud`) to the `[versions]` of the catalog. */
    fun parse(file: File, properties: Properties = Properties(), catalogVersions: Map<String, String> = emptyMap()): BuildGradleEntries {
        val content = file.readText()
        val effective = Properties().apply {
            putAll(properties)
            extractLocalVars(content).forEach { (k, v) -> setProperty(k, v) }
        }
        val unresolved = mutableListOf<DependencyEntry>()
        val dependencies = depRegex.findAll(content)
            .map { DependencyEntry(it.groupValues[1].trim(), it.groupValues[2].trim(), it.groupValues[3].trim()) }
            .mapNotNull { dep ->
                if (!dep.version.contains('$')) return@mapNotNull dep
                val resolved = resolveVar(dep.version, effective, catalogVersions)
                    ?: return@mapNotNull null.also { unresolved += dep }
                dep.copy(version = resolved)
            }
            .distinctBy { "${it.group}:${it.name}" }
            .toList()
        val plugins = pluginRegex.findAll(content)
            .map { PluginEntry(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .distinctBy { it.id }
            .toList()
        return BuildGradleEntries(dependencies, plugins, unresolved.distinctBy { "${it.group}:${it.name}" })
    }

    private fun extractLocalVars(content: String): Map<String, String> =
        localVarRegex.findAll(content).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resolveVar(version: String, properties: Properties, catalogVersions: Map<String, String>): String? {
        val match = varRegex.find(version) ?: return null
        val key = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
        catalogVersionRegex.find(key)?.let { return catalogVersions[it.groupValues[1]] }
        return properties.getProperty(key)
    }
}
