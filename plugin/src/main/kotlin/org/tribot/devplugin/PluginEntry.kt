package org.tribot.devplugin

import org.gradle.api.Named
import javax.inject.Inject

/**
 * A single automation-plugin entry that will appear in the generated `echo-plugins.json` manifest.
 *
 * See [ScriptEntry] for an explanation of why the display name is [pluginName] and not
 * `name`.
 */
abstract class PluginEntry @Inject constructor(private val entryName: String) : Named {
    override fun getName(): String = entryName

    var className: String = ""
    var pluginName: String = ""
    var version: String = "1.0.0"

    internal fun toMap(): Map<String, String> = mapOf(
        "className" to className,
        "name" to pluginName,
        "version" to version,
    )
}
