package one.epicsoft.gradle.parser

import java.io.File

data class DependencyEntry(val group: String, val name: String, val version: String)
data class BuildGradleEntries(val dependencies: List<DependencyEntry>, val plugins: List<PluginEntry>)

object BuildGradleParser {

    // Matches Groovy + Kotlin DSL:
    //   implementation "group:name:version"
    //   implementation("group:name:version")
    private val depRegex = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|classpath)\s*[("]+\s*["']?([^"':()]+):([^"':()]+):([^"'@:()]+)["']?\s*[")]+"""
    )

    // Matches Groovy + Kotlin DSL:
    //   id "plugin.id" version "x.y.z"
    //   id("plugin.id") version "x.y.z"
    private val pluginRegex = Regex(
        """id\s*[("]+([^"'()]+)[)"]+\s+version\s+["']([^"']+)["']"""
    )

    fun parse(file: File): BuildGradleEntries {
        val content = file.readText()
        val dependencies = depRegex.findAll(content)
            .map { DependencyEntry(it.groupValues[1].trim(), it.groupValues[2].trim(), it.groupValues[3].trim()) }
            .distinctBy { "${it.group}:${it.name}" }
            .toList()
        val plugins = pluginRegex.findAll(content)
            .map { PluginEntry(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .distinctBy { it.id }
            .toList()
        return BuildGradleEntries(dependencies, plugins)
    }
}
