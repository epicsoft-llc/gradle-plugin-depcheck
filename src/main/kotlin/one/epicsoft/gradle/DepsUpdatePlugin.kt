package one.epicsoft.gradle

import one.epicsoft.gradle.task.CheckUpdatesTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

class DepsUpdatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Guard: already applied (e.g. via root cascade + explicit subprojects { apply plugin: ... })
        if (project.extensions.findByName("depsUpdate") != null) return

        val extension = project.extensions.create("depsUpdate", DepsUpdateExtension::class.java)

        // For subprojects: inherit scalar defaults from root extension lazily (root may not be
        // configured yet at this point — convention providers resolve at execution time).
        val rootExt = if (project != project.rootProject)
            project.rootProject.extensions.findByType(DepsUpdateExtension::class.java)
        else null

        if (rootExt != null) {
            extension.verbose.convention(rootExt.verbose)
            extension.showAll.convention(rootExt.showAll)
            extension.failOnUpdates.convention(rootExt.failOnUpdates)
            extension.includePreRelease.convention(rootExt.includePreRelease)
            extension.checkGradleWrapper.convention(rootExt.checkGradleWrapper)
            extension.checkSubprojects.convention(rootExt.checkSubprojects)
        }

        fun registerTask(target: Project, ext: DepsUpdateExtension) {
            if (target.tasks.findByName("checkDependencyUpdates") != null) return

            // Resolve inherited collections lazily at execution time (after all depsUpdate {} blocks run).
            val effectiveExclude: Provider<List<String>> = if (rootExt != null)
                target.providers.provider {
                    resolveList(target.path, "exclude", ext.exclude.get(), rootExt.exclude.get(), ext.excludeMode.orNull)
                }
            else ext.exclude

            val effectiveMaxVersion: Provider<Map<String, String>> = if (rootExt != null)
                target.providers.provider {
                    resolveMap(target.path, "maxVersion", ext.maxVersion.get(), rootExt.maxVersion.get(), ext.maxVersionMode.orNull)
                }
            else ext.maxVersion

            target.tasks.register("checkDependencyUpdates", CheckUpdatesTask::class.java) {
                it.group = "dependency management"
                it.description = "Check for newer versions via deps.dev (libs.versions.toml + build.gradle)"
                it.verbose.set(ext.verbose)
                it.showAll.set(ext.showAll)
                it.failOnUpdates.set(ext.failOnUpdates)
                it.includePreRelease.set(ext.includePreRelease)
                it.exclude.set(effectiveExclude)
                it.maxVersion.set(effectiveMaxVersion)
                it.checkGradleWrapper.set(ext.checkGradleWrapper)
                it.checkSubprojects.set(ext.checkSubprojects)
            }
        }

        registerTask(project, extension)

        if (project == project.rootProject) {
            project.subprojects { sub ->
                sub.plugins.apply(DepsUpdatePlugin::class.java)
            }
        }
    }

    companion object {
        private fun parseMode(projectPath: String, paramName: String, raw: String?): CollectionInheritMode? {
            if (raw.isNullOrBlank()) return null
            return CollectionInheritMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "depsUpdate.$paramName has invalid value \"$raw\" in $projectPath. Use \"MERGE\" or \"OVERRIDE\"."
                )
        }

        fun resolveList(
            projectPath: String,
            paramName: String,
            sub: List<String>,
            parent: List<String>,
            rawMode: String?,
        ): List<String> = when {
            parent.isEmpty() -> sub
            sub.isEmpty()    -> parent
            else -> when (parseMode(projectPath, paramName, rawMode)) {
                CollectionInheritMode.MERGE    -> (parent + sub).distinct()
                CollectionInheritMode.OVERRIDE -> sub
                null -> throw GradleException(
                    "depsUpdate.$paramName is defined in both the root project and $projectPath. " +
                    "Set depsUpdate { ${paramName}Mode = \"MERGE\" } or \"OVERRIDE\" to resolve the conflict."
                )
            }
        }

        fun resolveMap(
            projectPath: String,
            paramName: String,
            sub: Map<String, String>,
            parent: Map<String, String>,
            rawMode: String?,
        ): Map<String, String> = when {
            parent.isEmpty() -> sub
            sub.isEmpty()    -> parent
            else -> when (parseMode(projectPath, paramName, rawMode)) {
                CollectionInheritMode.MERGE    -> parent + sub  // sub wins on key conflicts
                CollectionInheritMode.OVERRIDE -> sub
                null -> throw GradleException(
                    "depsUpdate.$paramName is defined in both the root project and $projectPath. " +
                    "Set depsUpdate { ${paramName}Mode = \"MERGE\" } or \"OVERRIDE\" to resolve the conflict."
                )
            }
        }
    }
}
