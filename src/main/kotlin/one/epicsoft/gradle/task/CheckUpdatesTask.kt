package one.epicsoft.gradle.task

import one.epicsoft.gradle.api.DepsDevClient
import one.epicsoft.gradle.api.Lookup
import one.epicsoft.gradle.api.MavenRepositoryClient
import one.epicsoft.gradle.api.VersionPattern
import one.epicsoft.gradle.api.VersionSources
import one.epicsoft.gradle.parser.BuildGradleParser
import one.epicsoft.gradle.parser.CatalogAliasUsages
import one.epicsoft.gradle.parser.LibraryEntry
import one.epicsoft.gradle.parser.PluginEntry
import one.epicsoft.gradle.parser.VersionCatalogParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future

private data class DepResult(val label: String, val coord: String, val mavenCoord: String, val current: String, val latest: String?, val hasUpdate: Boolean, val verboseUrl: String? = null)

private fun DepResult.format(verbose: Boolean): String {
    val prefix = "  [${label.padEnd(7)}]  "
    val base = when {
        hasUpdate -> "$prefix${coord.padEnd(55)}  $current  →  $latest"
        else      -> "$prefix${coord.padEnd(55)}  $current"
    }
    if (!verbose) return base
    val url = verboseUrl ?: "https://deps.dev/maven/$mavenCoord"
    return "$base\n    $url"
}

@DisableCachingByDefault(because = "Queries deps.dev API — result depends on external state")
abstract class CheckUpdatesTask : DefaultTask() {

    @get:Input
    abstract val verbose: Property<Boolean>

    @get:Input
    abstract val showAll: Property<Boolean>

    @get:Input
    abstract val failOnUpdates: Property<Boolean>

    @get:Input
    abstract val includePreRelease: Property<Boolean>

    @get:Input
    abstract val exclude: ListProperty<String>

    @get:Input
    abstract val maxVersion: MapProperty<String, String>

    @get:Input
    abstract val checkGradleWrapper: Property<Boolean>

    @get:Input
    abstract val checkSubprojects: Property<Boolean>

    @get:Input
    abstract val mavenRepositories: ListProperty<String>

    /** Set at configuration time — `Task.project` must not be touched during execution (an error from Gradle 10 on). */
    @get:Internal
    abstract val rootDirectory: Property<File>

    @get:Input
    abstract val runsInRootProject: Property<Boolean>

    /** Build files to scan: this project's own; for the root task with `checkSubprojects` those of every project. */
    @get:Internal
    abstract val buildFiles: ListProperty<File>

    @TaskAction
    fun checkUpdates() {
        val rootDir = rootDirectory.get()
        val repositories = mavenRepositories.get().map { it.trim() }.filter { it.isNotEmpty() }
        repositories.forEach { repository ->
            try {
                MavenRepositoryClient.requireSupported(repository)
            } catch (e: IllegalArgumentException) {
                throw GradleException("depsUpdate.mavenRepositories: ${e.message}")
            }
        }
        val sources = VersionSources(
            DepsDevClient(includePreRelease.get()),
            repositories.takeIf { it.isNotEmpty() }?.let { MavenRepositoryClient(it, includePreRelease.get()) },
        )
        val unknownTo = if (repositories.isEmpty()) "deps.dev" else "deps.dev and the configured repositories"
        val excludeSet = exclude.get().toSet()
        val maxVersions = maxVersion.get()
        val patterns = maxVersions.mapValues { (coord, value) ->
            try {
                VersionPattern.parse(value)
            } catch (e: IllegalArgumentException) {
                throw GradleException("maxVersion for '$coord': ${e.message}")
            }
        }
        val gradleProperties = loadGradleProperties(rootDir)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val jobs = mutableListOf<Future<DepResult?>>()
        val failed = ConcurrentLinkedQueue<String>()
        val unmatched = ConcurrentLinkedQueue<String>()
        val notChecked = ConcurrentLinkedQueue<String>()

        val isRoot = runsInRootProject.get()
        val buildFiles = buildFiles.get().filter { it.exists() }

        fun check(label: String, coord: String, mavenCoord: String, current: String) {
            jobs += executor.submit<DepResult?> {
                val pattern = patterns[coord]
                when (val lookup = sources.latest(mavenCoord, pattern)) {
                    is Lookup.Latest -> DepResult(label, coord, mavenCoord, current, lookup.version, hasUpdate(pattern, lookup.version, current), lookup.source)
                    is Lookup.Failed -> null.also { failed += "$coord (${lookup.reason})" }
                    Lookup.Unknown   -> null.also { notChecked += "$coord (unknown to $unknownTo)" }
                    Lookup.NoMatch   -> null.also {
                        if (pattern != null) unmatched += "$coord (maxVersion \"${maxVersions[coord]}\")"
                        else notChecked += "$coord (no stable version)"
                    }
                }
            }
        }

        try {
            val versionCatalog = listOf(
                File(rootDir, "gradle/libs.versions.toml"),
                File(rootDir, "libs.versions.toml"),
            ).firstOrNull { it.exists() }
            val catalog = versionCatalog?.let { VersionCatalogParser.parse(it) }
            if (versionCatalog != null && catalog != null) {
                logger.lifecycle("Scanning: ${versionCatalog.relativeTo(rootDir)}")

                val libraries: Iterable<LibraryEntry>
                val plugins: Iterable<PluginEntry>
                if (isRoot && checkSubprojects.get()) {
                    libraries = catalog.libraries
                    plugins = catalog.plugins
                } else {
                    val usedAliases = buildFiles
                        .map { BuildGradleParser.extractCatalogAliases(it) }
                        .fold(CatalogAliasUsages(emptySet(), emptySet())) { acc, u ->
                            CatalogAliasUsages(acc.libraryAccessors + u.libraryAccessors, acc.pluginAccessors + u.pluginAccessors)
                        }
                    libraries = usedAliases.libraryAccessors.mapNotNull { catalog.libraryByAccessor[it] }
                    plugins = usedAliases.pluginAccessors.mapNotNull { catalog.pluginByAccessor[it] }
                }

                libraries.forEach { lib ->
                    val coord = "${lib.group}:${lib.name}"
                    if (coord in excludeSet) return@forEach
                    check("library", coord, coord, lib.version)
                }

                plugins.forEach { plugin ->
                    if (plugin.id in excludeSet) return@forEach
                    check("plugin", plugin.id, "${plugin.id}:${plugin.id}.gradle.plugin", plugin.version)
                }
            }

            buildFiles.forEach { file ->
                logger.lifecycle("Scanning: ${file.relativeTo(rootDir)}")
                val entries = BuildGradleParser.parse(file, gradleProperties, catalog?.versionByAccessor.orEmpty())

                entries.dependencies.forEach { dep ->
                    val coord = "${dep.group}:${dep.name}"
                    if (coord in excludeSet) return@forEach
                    check("dep", coord, coord, dep.version)
                }

                entries.unresolved.forEach { dep ->
                    val coord = "${dep.group}:${dep.name}"
                    if (coord !in excludeSet) notChecked += "$coord (version ${dep.version} not resolvable)"
                }

                entries.plugins.forEach { plugin ->
                    if (plugin.id in excludeSet) return@forEach
                    check("plugin", plugin.id, "${plugin.id}:${plugin.id}.gradle.plugin", plugin.version)
                }
            }

            if (checkGradleWrapper.get()) {
                jobs += executor.submit<DepResult?> {
                    checkGradleWrapperVersion(rootDir, includePreRelease.get(), failed)
                }
            }

            val verbose = verbose.get()
            val showAll = showAll.get() || verbose
            val results = jobs.mapNotNull { it.get() }.sortedBy { it.coord }
            val updates = results.filter { it.hasUpdate }
            val upToDate = if (failed.isEmpty()) "All dependencies are up-to-date." else "All checked dependencies are up-to-date."

            if (showAll) {
                logger.lifecycle("\nChecked ${results.size} dependenc${if (results.size == 1) "y" else "ies"}:")
                results.forEach { logger.lifecycle(it.format(verbose)) }
                if (updates.isEmpty())
                    logger.lifecycle("\n$upToDate")
                else
                    logger.lifecycle("\n${updates.size} update(s) available.")
            } else {
                if (updates.isEmpty())
                    logger.lifecycle("\n$upToDate")
                else {
                    logger.lifecycle("\nAvailable updates (${updates.size}):")
                    updates.forEach { logger.lifecycle(it.format(verbose)) }
                }
            }

            if (unmatched.isNotEmpty()) {
                logger.warn("\nNo version matches the maxVersion pattern — check the configuration:")
                unmatched.sorted().forEach { logger.warn("  $it") }
            }
            if (failed.isNotEmpty()) {
                logger.warn("\nLookup failed for ${failed.size} dependenc${if (failed.size == 1) "y" else "ies"} — not checked, the result is incomplete:")
                failed.sorted().forEach { logger.warn("  $it") }
            }
            if (verbose && notChecked.isNotEmpty()) {
                logger.lifecycle("\nNot checked (${notChecked.size}):")
                notChecked.sorted().forEach { logger.lifecycle("  $it") }
            } else if (notChecked.isNotEmpty()) {
                // Counted without verbose too: a private artifact must not drop out of the check unnoticed.
                logger.lifecycle("\n${notChecked.size} not checked — verbose = true lists them and why.")
            }

            if (failOnUpdates.get() && updates.isNotEmpty())
                throw GradleException("${updates.size} dependency update(s) available — failing build (failOnUpdates = true).")
        } finally {
            executor.shutdown()
        }
    }

    private fun hasUpdate(pattern: VersionPattern?, latest: String, current: String): Boolean =
        pattern?.isUpdate(latest, current) ?: (DepsDevClient.compareVersions(latest, current) > 0)

    private fun loadGradleProperties(rootDir: File): Properties {
        val props = Properties()
        File(rootDir, "gradle.properties").takeIf { it.exists() }
            ?.inputStream()?.use { props.load(it) }
        return props
    }

    private fun checkGradleWrapperVersion(rootDir: File, includePreRelease: Boolean, failed: MutableCollection<String>): DepResult? {
        val wrapperProps = File(rootDir, "gradle/wrapper/gradle-wrapper.properties")
        if (!wrapperProps.exists()) return null

        val props = Properties()
        wrapperProps.inputStream().use { props.load(it) }
        val distUrl = props.getProperty("distributionUrl") ?: return null
        val current = Regex("""gradle-([0-9]+(?:\.[0-9]+(?:\.[0-9]+)?)?(?:-[a-zA-Z0-9]+)?)-""")
            .find(distUrl)?.groupValues?.get(1) ?: return null

        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val gson = Gson()

        // "release-candidate" answers {} while no RC is out - that is NoMatch, not a failure.
        fun fetchVersion(endpoint: String): Lookup {
            val req = HttpRequest.newBuilder(URI.create("https://services.gradle.org/versions/$endpoint"))
                .GET().timeout(Duration.ofSeconds(15)).build()
            return try {
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 200) return Lookup.Failed("HTTP ${resp.statusCode()}")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(resp.body(), type)
                val usable = map["broken"] != true && map["snapshot"] != true && map["nightly"] != true
                (map["version"] as? String)?.takeIf { usable }?.let { Lookup.Latest(it) } ?: Lookup.NoMatch
            } catch (e: Exception) {
                Lookup.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
        }

        val candidate = if (includePreRelease) fetchVersion("release-candidate") else Lookup.NoMatch
        return when (val lookup = candidate as? Lookup.Latest ?: fetchVersion("current")) {
            is Lookup.Latest -> DepResult("gradle", "Gradle Wrapper", "Gradle Wrapper", current, lookup.version,
                DepsDevClient.compareVersions(lookup.version, current) > 0, "https://gradle.org/releases/")
            is Lookup.Failed -> null.also { failed += "Gradle Wrapper (services.gradle.org: ${lookup.reason})" }
            else -> null
        }
    }
}
