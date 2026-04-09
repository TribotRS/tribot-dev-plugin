package org.tribot.devplugin

import org.gradle.api.Named
import javax.inject.Inject

/**
 * A single script entry that will appear in the generated `echo-scripts.json` manifest.
 *
 * The [entryName] is the container key from `scripts { register("key") { ... } }` and is
 * exposed via [getName] for Gradle's `NamedDomainObjectContainer`. The *display* name
 * shown to Tribot users is [scriptName]; the two are separated because `Named.getName()`
 * is reserved by Gradle for the container key.
 */
abstract class ScriptEntry @Inject constructor(private val entryName: String) : Named {
    override fun getName(): String = entryName

    var className: String = ""
    var scriptName: String = ""
    var version: String = "1.0.0"
    var author: String = ""
    var description: String = ""
    var category: String = ""

    internal fun toMap(): Map<String, String> = mapOf(
        "className" to className,
        "name" to scriptName,
        "version" to version,
        "author" to author,
        "description" to description,
        "category" to category,
    )
}
