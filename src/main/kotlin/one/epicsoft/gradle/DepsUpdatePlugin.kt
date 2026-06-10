package one.epicsoft.gradle

import one.epicsoft.gradle.task.CheckUpdatesTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class DepsUpdatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("depsUpdate", DepsUpdateExtension::class.java)

        fun registerOn(target: Project) {
            target.tasks.register("checkDependencyUpdates", CheckUpdatesTask::class.java) {
                it.group = "dependency management"
                it.description = "Check for newer versions via deps.dev (libs.versions.toml + build.gradle)"
                it.verbose.set(extension.verbose)
                it.showAll.set(extension.showAll)
                it.failOnUpdates.set(extension.failOnUpdates)
                it.includePreRelease.set(extension.includePreRelease)
                it.exclude.set(extension.exclude)
                it.maxVersion.set(extension.maxVersion)
                it.checkGradleWrapper.set(extension.checkGradleWrapper)
            }
        }

        registerOn(project)
        if (project == project.rootProject) {
            project.subprojects { sub -> registerOn(sub) }
        }
    }
}
