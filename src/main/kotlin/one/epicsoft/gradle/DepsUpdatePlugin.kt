package one.epicsoft.gradle

import one.epicsoft.gradle.task.CheckUpdatesTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class DepsUpdatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Guard: already applied (e.g. via root cascade + explicit subprojects { apply plugin: ... })
        if (project.extensions.findByName("depsUpdate") != null) return

        val extension = project.extensions.create("depsUpdate", DepsUpdateExtension::class.java)

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
            }
        }

        registerTask(project, extension)

        if (project == project.rootProject) {
            project.subprojects { sub ->
                // Apply plugin to subproject — creates its own extension + task
                sub.plugins.apply(DepsUpdatePlugin::class.java)
            }
        }
    }
}
