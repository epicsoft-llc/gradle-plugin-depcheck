package one.epicsoft.gradle

import org.gradle.api.provider.Property

abstract class DepsUpdateExtension {
    abstract val verbose: Property<Boolean>
    abstract val showAll: Property<Boolean>
    abstract val failOnUpdates: Property<Boolean>
    abstract val includePreRelease: Property<Boolean>

    init {
        verbose.convention(false)
        showAll.convention(false)
        failOnUpdates.convention(false)
        includePreRelease.convention(false)
    }
}
