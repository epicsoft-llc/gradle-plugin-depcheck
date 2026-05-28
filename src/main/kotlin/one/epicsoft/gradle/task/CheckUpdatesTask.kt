package one.epicsoft.gradle.task

import one.epicsoft.gradle.api.DepsDevClient
import one.epicsoft.gradle.parser.BuildGradleParser
import one.epicsoft.gradle.parser.VersionCatalogParser
import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

@DisableCachingByDefault(because = "Queries deps.dev API — result depends on external state")
abstract class CheckUpdatesTask : DefaultTask() {

    @TaskAction
    fun checkUpdates() {
        val rootDir = project.rootDir
        val client = DepsDevClient()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val jobs = mutableListOf<Future<String?>>()

        try {
            // --- libs.versions.toml ---
            val versionCatalog = File(rootDir, "gradle/libs.versions.toml")
            if (versionCatalog.exists()) {
                logger.lifecycle("Scanning: gradle/libs.versions.toml")
                val catalog = VersionCatalogParser.parse(versionCatalog)

                catalog.libraries.forEach { lib ->
                    jobs += executor.submit<String?> {
                        val coord = "${lib.group}:${lib.name}"
                        val latest = client.getLatestVersion("MAVEN", coord) ?: return@submit null
                        if (latest != lib.version) "  [library] $coord  ${lib.version}  →  $latest" else null
                    }
                }

                catalog.plugins.forEach { plugin ->
                    jobs += executor.submit<String?> {
                        val latest = client.getLatestVersion("MAVEN", "${plugin.id}:${plugin.id}.gradle.plugin") ?: return@submit null
                        if (latest != plugin.version) "  [plugin]  ${plugin.id}  ${plugin.version}  →  $latest" else null
                    }
                }
            }

            // --- build.gradle / build.gradle.kts ---
            collectBuildFiles(rootDir).forEach { file ->
                logger.lifecycle("Scanning: ${file.relativeTo(rootDir)}")
                val entries = BuildGradleParser.parse(file)

                entries.dependencies.forEach { dep ->
                    jobs += executor.submit<String?> {
                        val coord = "${dep.group}:${dep.name}"
                        val latest = client.getLatestVersion("MAVEN", coord) ?: return@submit null
                        if (latest != dep.version) "  [dep]     $coord  ${dep.version}  →  $latest" else null
                    }
                }

                entries.plugins.forEach { plugin ->
                    jobs += executor.submit<String?> {
                        val latest = client.getLatestVersion("MAVEN", "${plugin.id}:${plugin.id}.gradle.plugin") ?: return@submit null
                        if (latest != plugin.version) "  [plugin]  ${plugin.id}  ${plugin.version}  →  $latest" else null
                    }
                }
            }

            val updates = jobs.mapNotNull { it.get() }.sorted()
            if (updates.isEmpty()) {
                logger.lifecycle("\nAll dependencies are up-to-date.")
            } else {
                logger.lifecycle("\nAvailable updates (${updates.size}):")
                updates.forEach { logger.lifecycle(it) }
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
