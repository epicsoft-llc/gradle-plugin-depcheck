package one.epicsoft.gradle.task

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
        File(dir, "build.gradle").writeText(
            "plugins { id 'one.epicsoft.depcheck' }\n" +
                "depsUpdate { checkGradleWrapper = false; mavenRepositories = ['https://repo.example.com/maven'] }\n"
        )
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

    @Test
    fun `credentials in a repository URL are refused without being printed`() {
        File(dir, "settings.gradle").writeText("rootProject.name = 'sample'\n")
        File(dir, "build.gradle").writeText(
            "plugins { id 'one.epicsoft.depcheck' }\n" +
                "depsUpdate { checkGradleWrapper = false; mavenRepositories = ['https://user:s3cr3t@repo.example.com/maven'] }\n"
        )

        val output = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(":checkDependencyUpdates")
            .buildAndFail()
            .output

        assertContains(output, "credentials in the URL are not supported (host repo.example.com)")
        assertFalse(output.contains("s3cr3t"), output)
    }

    @Test
    fun `the old plugin id still works and warns once`() {
        File(dir, "settings.gradle").writeText("rootProject.name = 'sample'\ninclude 'sub'\n")
        File(dir, "build.gradle").writeText(
            "plugins { id 'one.epicsoft.deps-update' }\n" +
                "depsUpdate { checkGradleWrapper = false }\n" +
                "subprojects { apply plugin: 'one.epicsoft.deps-update' }\n"
        )
        File(dir, "sub").mkdirs()
        File(dir, "sub/build.gradle").writeText("")

        val output = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(":checkDependencyUpdates", ":sub:checkDependencyUpdates")
            .build()
            .output

        assertEquals(1, output.split("Plugin id \"one.epicsoft.deps-update\" is deprecated").size - 1, output)
        assertContains(output, "apply \"one.epicsoft.depcheck\" instead (root project 'sample')")
        assertContains(output, "All dependencies are up-to-date.")
    }
}
