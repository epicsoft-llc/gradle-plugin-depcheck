package one.epicsoft.gradle

import org.gradle.api.provider.Property

abstract class DepsUpdateExtension {
    abstract val verbose: Property<Boolean>

    init {
        verbose.convention(false)
    }
}
