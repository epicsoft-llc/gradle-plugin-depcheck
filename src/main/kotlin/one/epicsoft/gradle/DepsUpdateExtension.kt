package one.epicsoft.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

enum class CollectionInheritMode { MERGE, OVERRIDE }

abstract class DepsUpdateExtension {
    abstract val verbose: Property<Boolean>
    abstract val showAll: Property<Boolean>
    abstract val failOnUpdates: Property<Boolean>
    abstract val includePreRelease: Property<Boolean>
    abstract val exclude: ListProperty<String>
    abstract val maxVersion: MapProperty<String, String>
    abstract val checkGradleWrapper: Property<Boolean>
    abstract val checkSubprojects: Property<Boolean>

    /** Required when both root and this subproject define [exclude]. Values: "MERGE" or "OVERRIDE". */
    abstract val excludeMode: Property<String>

    /** Required when both root and this subproject define [maxVersion]. Values: "MERGE" or "OVERRIDE". */
    abstract val maxVersionMode: Property<String>

    init {
        verbose.convention(false)
        showAll.convention(false)
        failOnUpdates.convention(false)
        includePreRelease.convention(false)
        exclude.convention(emptyList())
        maxVersion.convention(emptyMap())
        checkGradleWrapper.convention(true)
        checkSubprojects.convention(true)
    }
}
