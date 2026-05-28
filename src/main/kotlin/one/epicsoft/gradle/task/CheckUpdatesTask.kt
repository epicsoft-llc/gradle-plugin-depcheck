package one.epicsoft.gradle.task

import one.epicsoft.gradle.api.DepsDevClient
import one.epicsoft.gradle.parser.BuildGradleParser
import one.epicsoft.gradle.parser.VersionCatalogParser
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

private data class DepResult(val message: String, val hasUpdate: Boolean)

@DisableCachingByDefault(because = "Queries deps.dev API — result depends on external state")
abstract class CheckUpdatesTask : DefaultTask() {

    @get:Input
    abstract val verbose: Property<Boolean>

    @TaskAction
    fun checkUpdates() {
        val rootDir = project.rootDir
        val client = DepsDevClient()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val jobs = mutableListOf<Future<DepResult?>>()

        try {
            // --- libs.versions.toml ---
            val versionCatalog = File(rootDir, "gradle/libs.versions.toml")
            if (versionCatalog.exists()) {
                logger.lifecycle("Scanning: gradle/libs.versions.toml")
                val catalog = VersionCatalogParser.parse(versionCatalog)

                catalog.libraries.forEach { lib ->
                    jobs += executor.submit<DepResult?> {
                        val coord = "${lib.group}:${lib.name}"
                        val latest = client.getLatestVersion("MAVEN", coord) ?: return@submit null
                        if (latest != lib.version)
                            DepResult("  [library] $coord  ${lib.version}  →  $latest", true)
                        else
                            DepResult("  [library] $coord  ${lib.version}", false)
                    }
                }

                catalog.plugins.forEach { plugin ->
                    jobs += executor.submit<DepResult?> {
                        val latest = client.getLatestVersion("MAVEN", "${plugin.id}:${plugin.id}.gradle.plugin") ?: return@submit null
                        if (latest != plugin.version)
                            DepResult("  [plugin]  ${plugin.id}  ${plugin.version}  →  $latest", true)
                        else
                            DepResult("  [plugin]  ${plugin.id}  ${plugin.version}", false)
                    }
                }
            }

            // --- build.gradle / build.gradle.kts ---
            collectBuildFiles(rootDir).forEach { file ->
                logger.lifecycle("Scanning: ${file.relativeTo(rootDir)}")
                val entries = BuildGradleParser.parse(file)

                entries.dependencies.forEach { dep ->
                    jobs += executor.submit<DepResult?> {
                        val coord = "${dep.group}:${dep.name}"
                        val latest = client.getLatestVersion("MAVEN", coord) ?: return@submit null
                        if (latest != dep.version)
                            DepResult("  [dep]     $coord  ${dep.version}  →  $latest", true)
                        else
                            DepResult("  [dep]     $coord  ${dep.version}", false)
                    }
                }

                entries.plugins.forEach { plugin ->
                    jobs += executor.submit<DepResult?> {
                        val latest = client.getLatestVersion("MAVEN", "${plugin.id}:${plugin.id}.gradle.plugin") ?: return@submit null
                        if (latest != plugin.version)
                            DepResult("  [plugin]  ${plugin.id}  ${plugin.version}  →  $latest", true)
                        else
                            DepResult("  [plugin]  ${plugin.id}  ${plugin.version}", false)
                    }
                }
            }

            val results = jobs.mapNotNull { it.get() }.sortedBy { it.message }
            val updates = results.filter { it.hasUpdate }

            if (verbose.get()) {
                logger.lifecycle("\nChecked ${results.size} dependenc${if (results.size == 1) "y" else "ies"}:")
                results.forEach { logger.lifecycle(it.message) }
                if (updates.isEmpty())
                    logger.lifecycle("\nAll dependencies are up-to-date.")
                else
                    logger.lifecycle("\n${updates.size} update(s) available.")
            } else {
                if (updates.isEmpty())
                    logger.lifecycle("\nAll dependencies are up-to-date.")
                else {
                    logger.lifecycle("\nAvailable updates (${updates.size}):")
                    updates.forEach { logger.lifecycle(it.message) }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    private fun collectBuildFiles(rootDir: File): List<File> =
        listOf("build.gradle", "build.gradle.kts")
            .map { File(rootDir, it) }
            .filter { it.exists() } +
        project.subprojects.flatMap { sub ->
            listOf("build.gradle", "build.gradle.kts")
                .map { File(sub.projectDir, it) }
                .filter { it.exists() }
        }
}
