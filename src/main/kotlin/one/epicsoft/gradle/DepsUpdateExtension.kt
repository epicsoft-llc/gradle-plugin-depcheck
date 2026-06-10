package one.epicsoft.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

abstract class DepsUpdateExtension {
    abstract val verbose: Property<Boolean>
    abstract val showAll: Property<Boolean>
    abstract val failOnUpdates: Property<Boolean>
    abstract val includePreRelease: Property<Boolean>
    abstract val exclude: ListProperty<String>
    abstract val maxVersion: MapProperty<String, String>
    abstract val checkGradleWrapper: Property<Boolean>

    init {
        verbose.convention(false)
        showAll.convention(false)
        failOnUpdates.convention(false)
        includePreRelease.convention(false)
        exclude.convention(emptyList())
        maxVersion.convention(emptyMap())
        checkGradleWrapper.convention(true)
    }
}
