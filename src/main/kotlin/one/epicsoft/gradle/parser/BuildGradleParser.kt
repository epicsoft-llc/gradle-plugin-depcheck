package one.epicsoft.gradle.parser

import java.io.File
import java.util.Properties

data class DependencyEntry(val group: String, val name: String, val version: String)
data class BuildGradleEntries(val dependencies: List<DependencyEntry>, val plugins: List<PluginEntry>)

object BuildGradleParser {

    private val depRegex = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly|testAnnotationProcessor|annotationProcessor|developmentOnly|classpath)\s*\(?\s*["']([^"':()]+):([^"'@:()]+):([^"'@:()]+)["']\s*\)?"""
    )

    private val pluginRegex = Regex(
        """id\s*\(?\s*["']([^"'()]+)["']\s*\)?\s+version\s+["']([^"']+)["']"""
    )

    private val varRegex = Regex("""\$\{([^}]+)}|\$([A-Za-z_][A-Za-z0-9_]*)""")

    // def x = "v" / final def x = "v" / val x = "v" / ext.x = "v"
    private val localVarRegex = Regex(
        """(?:(?:final\s+)?(?:def|val)\s+|ext\.)(\w+)\s*=\s*["']([^"']+)["']"""
    )

    fun parse(file: File, properties: Properties = Properties()): BuildGradleEntries {
        val content = file.readText()
        val effective = Properties().apply {
            putAll(properties)
            extractLocalVars(content).forEach { (k, v) -> setProperty(k, v) }
        }
        val dependencies = depRegex.findAll(content)
            .map { DependencyEntry(it.groupValues[1].trim(), it.groupValues[2].trim(), it.groupValues[3].trim()) }
            .mapNotNull { dep ->
                if (!dep.version.contains('$')) return@mapNotNull dep
                val resolved = resolveVar(dep.version, effective) ?: return@mapNotNull null
                dep.copy(version = resolved)
            }
            .distinctBy { "${it.group}:${it.name}" }
            .toList()
        val plugins = pluginRegex.findAll(content)
            .map { PluginEntry(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .distinctBy { it.id }
            .toList()
        return BuildGradleEntries(dependencies, plugins)
    }

    private fun extractLocalVars(content: String): Map<String, String> =
        localVarRegex.findAll(content).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resolveVar(version: String, properties: Properties): String? {
        val match = varRegex.find(version) ?: return null
        val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return properties.getProperty(key)
    }
}
