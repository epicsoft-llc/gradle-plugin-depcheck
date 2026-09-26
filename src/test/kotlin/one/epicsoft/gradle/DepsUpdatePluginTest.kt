package one.epicsoft.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Inheritance of `exclude` / `maxVersion` between root and subproject. */
class DepsUpdatePluginTest {

    @Test
    fun `one side defined - that side wins`() {
        assertEquals(listOf("a"), DepsUpdatePlugin.resolveList(":core", "exclude", listOf("a"), emptyList(), null))
        assertEquals(listOf("r"), DepsUpdatePlugin.resolveList(":core", "exclude", emptyList(), listOf("r"), null))
    }

    @Test
    fun `MERGE combines, OVERRIDE keeps the subproject`() {
        assertEquals(listOf("r", "a"), DepsUpdatePlugin.resolveList(":core", "exclude", listOf("a", "r"), listOf("r"), "merge"))
        assertEquals(listOf("a"), DepsUpdatePlugin.resolveList(":core", "exclude", listOf("a"), listOf("r"), "OVERRIDE"))
        assertEquals(
            mapOf("x" to "2", "y" to "1"),
            DepsUpdatePlugin.resolveMap(":core", "maxVersion", mapOf("x" to "2"), mapOf("x" to "1", "y" to "1"), "MERGE"),
        )
    }

    @Test
    fun `both defined without a mode or with an unknown mode fails`() {
        assertFailsWith<GradleException> { DepsUpdatePlugin.resolveList(":core", "exclude", listOf("a"), listOf("r"), null) }
        assertFailsWith<GradleException> { DepsUpdatePlugin.resolveMap(":core", "maxVersion", mapOf("x" to "2"), mapOf("y" to "1"), "BOTH") }
    }
}
