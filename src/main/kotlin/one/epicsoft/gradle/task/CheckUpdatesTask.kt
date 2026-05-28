package one.epicsoft.gradle.task

import one.epicsoft.gradle.api.DepsDevClient
import one.epicsoft.gradle.parser.BuildGradleParser
import one.epicsoft.gradle.parser.VersionCatalogParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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

    @TaskAction
    fun checkUpdates() {
        val rootDir = project.rootDir
        val client = DepsDevClient(includePreRelease.get())
        val gradleProperties = loadGradleProperties(rootDir)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val jobs = mutableListOf<Future<DepResult?>>()

        try {
            val versionCatalog = File(rootDir, "gradle/libs.versions.toml")
            if (versionCatalog.exists()) {
                logger.lifecycle("Scanning: gradle/libs.versions.toml")
                val catalog = VersionCatalogParser.parse(versionCatalog)

                catalog.libraries.forEach { lib ->
                    jobs += executor.submit<DepResult?> {
                        val coord = "${lib.group}:${lib.name}"
                        val latest = client.getLatestVersion("MAVEN", coord) ?: return@submit null
                        DepResult("library", coord, coord, lib.version, latest, latest != lib.version)
                    }
                }

                catalog.plugins.forEach { plugin ->
                    jobs += executor.submit<DepResult?> {
                        val mavenCoord = "${plugin.id}:${plugin.id}.gradle.plugin"
                        val latest = client.getLatestVersion("MAVEN", mavenCoord) ?: return@submit null
                        DepResult("plugin", plugin.id, mavenCoord, plugin.version, latest, latest != plugin.version)
                    }
                }
            }

            collectBuildFiles().forEach { file ->
                logger.lifecycle("Scanning: ${file.relativeTo(rootDir)}")
                val entries = BuildGradleParser.parse(file, gradleProperties)

                entries.dependencies.forEach { dep ->
                    jobs += executor.submit<DepResult?> {
                        val coord = "${dep.group}:${dep.name}"
                        val latest = client.getLatestVersion("MAVEN", coord) ?: return@submit null
                        DepResult("dep", coord, coord, dep.version, latest, latest != dep.version)
                    }
                }

                entries.plugins.forEach { plugin ->
                    jobs += executor.submit<DepResult?> {
                        val mavenCoord = "${plugin.id}:${plugin.id}.gradle.plugin"
                        val latest = client.getLatestVersion("MAVEN", mavenCoord) ?: return@submit null
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

    private fun collectBuildFiles(): List<File> =
        project.rootProject.allprojects.flatMap { p ->
            listOf("build.gradle", "build.gradle.kts")
                .map { File(p.projectDir, it) }
                .filter { it.exists() }
        }
}
