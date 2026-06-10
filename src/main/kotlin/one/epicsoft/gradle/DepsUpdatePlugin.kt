package one.epicsoft.gradle

import one.epicsoft.gradle.task.CheckUpdatesTask
import org.gradle.api.Plugin
import org.gradle.api.Project

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
        }

        fun registerTask(target: Project, ext: DepsUpdateExtension) {
            if (target.tasks.findByName("checkDependencyUpdates") != null) return
            target.tasks.register("checkDependencyUpdates", CheckUpdatesTask::class.java) {
                it.group = "dependency management"
                it.description = "Check for newer versions via deps.dev (libs.versions.toml + build.gradle)"
                it.verbose.set(ext.verbose)
                it.showAll.set(ext.showAll)
                it.failOnUpdates.set(ext.failOnUpdates)
                it.includePreRelease.set(ext.includePreRelease)
                it.exclude.set(ext.exclude)
                it.maxVersion.set(ext.maxVersion)
                it.checkGradleWrapper.set(ext.checkGradleWrapper)
                it.excludeMode.set(ext.excludeMode)
                it.maxVersionMode.set(ext.maxVersionMode)
                if (rootExt != null) {
                    it.rootExclude.set(rootExt.exclude)
                    it.rootMaxVersion.set(rootExt.maxVersion)
                } else {
                    it.rootExclude.set(emptyList())
                    it.rootMaxVersion.set(emptyMap())
                }
            }
        }

        registerTask(project, extension)

        if (project == project.rootProject) {
            project.subprojects { sub ->
                sub.plugins.apply(DepsUpdatePlugin::class.java)
            }
        }
    }
}
