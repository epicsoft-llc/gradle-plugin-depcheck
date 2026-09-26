package one.epicsoft.gradle.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VersionPatternTest {

    @ParameterizedTest(name = "\"{0}\" matches {1}: {2}")
    @CsvSource(
        "3,      3.5.2,   true",
        "3,      4.0.0,   false",
        "2.55,   2.55.6,  true",
        "2.55,   2.56.0,  false",
        "2.x,    2.56.0,  true",
        "2.x,    3.0.0,   false",
        "2.56.x, 2.56.1,  true",
        "2.56.x, 2.57.0,  false",
        "x,      9.0.0,   true",
        "x.x,    1.2.3,   true",
    )
    fun matches(pattern: String, version: String, expected: Boolean) {
        assertEquals(expected, VersionPattern.parse(pattern).matches(version))
    }

    @ParameterizedTest(name = "\"{0}\": {2} over {1} is an update: {3}")
    @CsvSource(
        // without x every newer version counts, as before
        "2.55,   2.55.5, 2.55.6, true",
        "3,      3.2.1,  3.2.2,  true",
        // x sets the counting level
        "2.x,    2.55.5, 2.55.6, false",
        "2.x,    2.55.5, 2.56.0, true",
        "2.56.x, 2.56.1, 2.56.2, true",
        "x,      2.55.5, 2.99.0, false",
        "x,      2.55.5, 3.0.0,  true",
        "x.x,    2.55.5, 2.55.9, false",
        "x.x,    2.55.5, 2.56.0, true",
    )
    fun isUpdate(pattern: String, current: String, latest: String, expected: Boolean) {
        assertEquals(expected, VersionPattern.parse(pattern).isUpdate(latest, current))
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = ["", "2.x.5", "x.2", "2..x", "a", "2.-1", "2.55.*.1"])
    fun rejectsInvalid(pattern: String) {
        assertFailsWith<IllegalArgumentException> { VersionPattern.parse(pattern) }
    }

    @Test
    fun acceptsWildcardVariants() {
        listOf("2.X", "2.*", "x", "x.x.x").forEach { VersionPattern.parse(it) }
    }
}
