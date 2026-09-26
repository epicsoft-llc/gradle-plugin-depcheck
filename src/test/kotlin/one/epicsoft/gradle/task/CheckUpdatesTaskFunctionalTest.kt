package one.epicsoft.gradle.task

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

/**
 * Runs the task in a real build with the configuration cache and `--warning-mode=fail`: a `Task.project` access at
 * execution time fails it (an error from Gradle 10 on). The build declares nothing to look up, so no network is needed.
 */
class CheckUpdatesTaskFunctionalTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `runs from the configuration cache without deprecations`() {
        File(dir, "settings.gradle").writeText("rootProject.name = 'sample'\ninclude 'sub'\n")
        File(dir, "build.gradle").writeText("plugins { id 'one.epicsoft.deps-update' }\ndepsUpdate { checkGradleWrapper = false }\n")
        File(dir, "sub").mkdirs()
        File(dir, "sub/build.gradle").writeText("")

        val runner = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(":checkDependencyUpdates", "--configuration-cache", "--warning-mode=fail")

        assertContains(runner.build().output, "Configuration cache entry stored")
        val reused = runner.build().output
        assertContains(reused, "Configuration cache entry reused")
        assertContains(reused, "All dependencies are up-to-date.")
    }
}
