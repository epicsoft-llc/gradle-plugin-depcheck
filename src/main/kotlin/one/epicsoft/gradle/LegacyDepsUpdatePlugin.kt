package one.epicsoft.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The plugin id up to 0.4.1, `one.epicsoft.deps-update`. Still published, so existing builds keep working and the update
 * check keeps reporting new releases under it; applies [DepsUpdatePlugin] and asks for the switch to `one.epicsoft.depcheck`.
 */
class LegacyDepsUpdatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // One warning per build: a subproject applying the old id under a root that already did stays silent.
        val rootWarned = project != project.rootProject &&
            project.rootProject.plugins.hasPlugin(LegacyDepsUpdatePlugin::class.java)
        if (!rootWarned) {
            project.logger.warn(
                "Plugin id \"one.epicsoft.deps-update\" is deprecated and will be removed in a later release — " +
                    "apply \"one.epicsoft.depcheck\" instead (${project.displayName}). The depsUpdate {} configuration stays the same."
            )
        }
        project.plugins.apply(DepsUpdatePlugin::class.java)
    }
}
