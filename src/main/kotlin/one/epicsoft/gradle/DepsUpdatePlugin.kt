package one.epicsoft.gradle

import one.epicsoft.gradle.task.CheckUpdatesTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class DepsUpdatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("checkDependencyUpdates", CheckUpdatesTask::class.java) {
            it.group = "dependency management"
            it.description = "Check for newer versions via deps.dev (libs.versions.toml + build.gradle)"
        }
    }
}
