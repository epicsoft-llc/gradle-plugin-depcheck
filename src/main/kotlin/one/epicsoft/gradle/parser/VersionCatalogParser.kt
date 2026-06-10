package one.epicsoft.gradle.parser

import java.io.File

data class LibraryEntry(val group: String, val name: String, val version: String)
data class PluginEntry(val id: String, val version: String)

data class VersionCatalog(
    val libraries: List<LibraryEntry>,
    val plugins: List<PluginEntry>,
    val libraryByAccessor: Map<String, LibraryEntry>,
    val pluginByAccessor: Map<String, PluginEntry>,
)

object VersionCatalogParser {

    fun parse(file: File): VersionCatalog {
        val sections = parseSections(file.readText())
        val versions = parseVersions(sections["versions"] ?: "")
        val libPairs = parseLibraries(sections["libraries"] ?: "", versions)
        val pluginPairs = parsePlugins(sections["plugins"] ?: "", versions)
        return VersionCatalog(
            libraries = libPairs.map { it.second },
            plugins = pluginPairs.map { it.second },
            libraryByAccessor = libPairs.associate { (alias, entry) -> aliasToAccessor(alias) to entry },
            pluginByAccessor = pluginPairs.associate { (alias, entry) -> aliasToAccessor(alias) to entry },
        )
    }

    fun aliasToAccessor(alias: String): String = alias.replace('-', '.')

    private fun parseSections(content: String): Map<String, String> {
        val result = mutableMapOf<String, StringBuilder>()
        var current: String? = null
        for (line in content.lines()) {
            val section = Regex("^\\[([a-zA-Z.]+)]").find(line.trim())?.groupValues?.get(1)
            if (section != null) {
                current = section
            } else {
                current?.let { result.getOrPut(it) { StringBuilder() }.appendLine(line) }
            }
        }
        return result.mapValues { it.value.toString() }
    }

    private fun parseVersions(section: String): Map<String, String> =
        section.lines().mapNotNull { line ->
            Regex("""^([\w-]+)\s*=\s*["']([^"']+)["']""")
                .find(line.trim())
                ?.let { it.groupValues[1] to it.groupValues[2] }
        }.toMap()

    private fun parseLibraries(section: String, versions: Map<String, String>): List<Pair<String, LibraryEntry>> =
        section.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null

            val alias = Regex("""^([\w-]+)\s*=""").find(t)?.groupValues?.get(1) ?: return@mapNotNull null

            // String notation: alias = "group:name:version"
            Regex("""["']([^"']+):([^"']+):([^"']+)["']""").find(t)?.let {
                return@mapNotNull alias to LibraryEntry(it.groupValues[1], it.groupValues[2], it.groupValues[3])
            }

            // Module shorthand: { module = "group:name", version.ref = "..." }
            Regex("""module\s*=\s*["']([^"':]+):([^"':]+)["']""").find(t)?.let { moduleMatch ->
                val group = moduleMatch.groupValues[1]
                val name = moduleMatch.groupValues[2]
                val vRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1)
                val vDirect = Regex("""(?<!\.)version\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1)
                val version = versions[vRef] ?: vDirect ?: return@mapNotNull null
                return@mapNotNull alias to LibraryEntry(group, name, version)
            }

            // Inline table: { group = "...", name = "...", version[.ref] = "..." }
            val group = Regex("""group\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = Regex("""name\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1) ?: return@mapNotNull null
            val vRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1)
            val vDirect = Regex("""(?<!\.)version\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1)
            val version = versions[vRef] ?: vDirect ?: return@mapNotNull null

            alias to LibraryEntry(group, name, version)
        }

    private fun parsePlugins(section: String, versions: Map<String, String>): List<Pair<String, PluginEntry>> =
        section.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null

            val alias = Regex("""^([\w-]+)\s*=""").find(t)?.groupValues?.get(1) ?: return@mapNotNull null
            val id = Regex("""id\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1) ?: return@mapNotNull null
            val vRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1)
            val vDirect = Regex("""(?<!\.)version\s*=\s*["']([^"']+)["']""").find(t)?.groupValues?.get(1)
            val version = versions[vRef] ?: vDirect ?: return@mapNotNull null

            alias to PluginEntry(id, version)
        }
}
