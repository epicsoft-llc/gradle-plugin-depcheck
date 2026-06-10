package one.epicsoft.gradle.task

import one.epicsoft.gradle.api.DepsDevClient
import one.epicsoft.gradle.parser.BuildGradleParser
import one.epicsoft.gradle.parser.CatalogAliasUsages
import one.epicsoft.gradle.parser.LibraryEntry
import one.epicsoft.gradle.parser.PluginEntry
import one.epicsoft.gradle.parser.VersionCatalogParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.Future

private data class DepResult(val label: String, val coord: String, val mavenCoord: String, val current: String, val latest: String?, val hasUpdate: Boolean)

private fun DepResult.format(verbose: Boolean): String {
    val prefix = "  [${label.padEnd(7)}]  "
    val base = when {
        hasUpdate -> "$prefix${coord.padEnd(55)}  $current  →  $latest"
        else      -> "$prefix${coord.padEnd(55)}  $current"
    }
    return if (verbose) "$base\n    https://deps.dev/maven/$mavenCoord" else base
}

@DisableCachingByDefault(because = "Queries deps.dev API — result depends on external state")
abstract class CheckUpdatesTask : DefaultTask() {

    @get:Input
    abstract val verbose: Property<Boolean>

    @get:Input
    abstract val showAll: Property<Boolean>

    @get:Input
    abstract val failOnUpdates: Property<Boolean>

    @get:Input
    abstract val includePreRelease: Property<Boolean>

    @get:Input
    abstract val exclude: ListProperty<String>

    @get:Input
    abstract val maxVersion: MapProperty<String, String>

    @TaskAction
    fun checkUpdates() {
        val rootDir = project.rootDir
        val client = DepsDevClient(includePreRelease.get())
        val excludeSet = exclude.get().toSet()
        val maxVersionMap = maxVersion.get()
        val gradleProperties = loadGradleProperties(rootDir)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val jobs = mutableListOf<Future<DepResult?>>()

        val isRoot = project == project.rootProject
        val buildFiles = collectBuildFiles()

        try {
            val versionCatalog = listOf(
                File(rootDir, "gradle/libs.versions.toml"),
                File(rootDir, "libs.versions.toml"),
            ).firstOrNull { it.exists() }
            if (versionCatalog != null) {
                logger.lifecycle("Scanning: ${versionCatalog.relativeTo(rootDir)}")
                val catalog = VersionCatalogParser.parse(versionCatalog)

                val libraries: Iterable<LibraryEntry>
                val plugins: Iterable<PluginEntry>
                if (isRoot) {
                    libraries = catalog.libraries
                    plugins = catalog.plugins
                } else {
                    val usedAliases = buildFiles
                        .map { BuildGradleParser.extractCatalogAliases(it) }
                        .fold(CatalogAliasUsages(emptySet(), emptySet())) { acc, u ->
                            CatalogAliasUsages(acc.libraryAccessors + u.libraryAccessors, acc.pluginAccessors + u.pluginAccessors)
                        }
                    libraries = usedAliases.libraryAccessors.mapNotNull { catalog.libraryByAccessor[it] }
                    plugins = usedAliases.pluginAccessors.mapNotNull { catalog.pluginByAccessor[it] }
                }

                libraries.forEach { lib ->
                    val coord = "${lib.group}:${lib.name}"
                    if (coord in excludeSet) return@forEach
                    jobs += executor.submit<DepResult?> {
                        val latest = client.getLatestVersion("MAVEN", coord, maxVersionMap[coord]) ?: return@submit null
                        DepResult("library", coord, coord, lib.version, latest, latest != lib.version)
                    }
                }

                plugins.forEach { plugin ->
                    if (plugin.id in excludeSet) return@forEach
                    jobs += executor.submit<DepResult?> {
                        val mavenCoord = "${plugin.id}:${plugin.id}.gradle.plugin"
                        val latest = client.getLatestVersion("MAVEN", mavenCoord, maxVersionMap[plugin.id]) ?: return@submit null
                        DepResult("plugin", plugin.id, mavenCoord, plugin.version, latest, latest != plugin.version)
                    }
                }
            }

            buildFiles.forEach { file ->
                logger.lifecycle("Scanning: ${file.relativeTo(rootDir)}")
                val entries = BuildGradleParser.parse(file, gradleProperties)

                entries.dependencies.forEach { dep ->
                    val coord = "${dep.group}:${dep.name}"
                    if (coord in excludeSet) return@forEach
                    jobs += executor.submit<DepResult?> {
                        val latest = client.getLatestVersion("MAVEN", coord, maxVersionMap[coord]) ?: return@submit null
                        DepResult("dep", coord, coord, dep.version, latest, latest != dep.version)
                    }
                }

                entries.plugins.forEach { plugin ->
                    if (plugin.id in excludeSet) return@forEach
                    jobs += executor.submit<DepResult?> {
                        val mavenCoord = "${plugin.id}:${plugin.id}.gradle.plugin"
                        val latest = client.getLatestVersion("MAVEN", mavenCoord, maxVersionMap[plugin.id]) ?: return@submit null
                        DepResult("plugin", plugin.id, mavenCoord, plugin.version, latest, latest != plugin.version)
                    }
                }
            }

            val verbose = verbose.get()
            val showAll = showAll.get() || verbose
            val results = jobs.mapNotNull { it.get() }.sortedBy { it.coord }
            val updates = results.filter { it.hasUpdate }

            if (showAll) {
                logger.lifecycle("\nChecked ${results.size} dependenc${if (results.size == 1) "y" else "ies"}:")
                results.forEach { logger.lifecycle(it.format(verbose)) }
                if (updates.isEmpty())
                    logger.lifecycle("\nAll dependencies are up-to-date.")
                else
                    logger.lifecycle("\n${updates.size} update(s) available.")
            } else {
                if (updates.isEmpty())
                    logger.lifecycle("\nAll dependencies are up-to-date.")
                else {
                    logger.lifecycle("\nAvailable updates (${updates.size}):")
                    updates.forEach { logger.lifecycle(it.format(verbose)) }
                }
            }

            if (failOnUpdates.get() && updates.isNotEmpty())
                throw GradleException("${updates.size} dependency update(s) available — failing build (failOnUpdates = true).")
        } finally {
            executor.shutdown()
        }
    }

    private fun loadGradleProperties(rootDir: File): Properties {
        val props = Properties()
        File(rootDir, "gradle.properties").takeIf { it.exists() }
            ?.inputStream()?.use { props.load(it) }
        return props
    }

    private fun collectBuildFiles(): List<File> {
        val projects = if (project == project.rootProject)
            project.rootProject.allprojects
        else
            listOf(project)
        return projects.flatMap { p ->
            listOf("build.gradle", "build.gradle.kts")
                .map { File(p.projectDir, it) }
                .filter { it.exists() }
        }
    }
}
