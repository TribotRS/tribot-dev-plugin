package org.tribot.devplugin

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Type-safe DSL exposed as the `tribot { }` block in consumer build scripts.
 *
 * Defaults reflect the most common consumer setup (Script SDK on, legacy off, Compose
 * and JavaFX on). The automation-plugin container is named `automationPlugins` to avoid
 * clashing with Gradle's top-level `plugins { }` block inside the `tribot { }` closure.
 */
abstract class TribotDevExtension @Inject constructor(objects: ObjectFactory) {

    var useScriptSdk: Boolean = true
    var useLegacyApi: Boolean = false
    var useCompose: Boolean = true
    var useJavaFx: Boolean = true

    val scripts: NamedDomainObjectContainer<ScriptEntry> =
        objects.domainObjectContainer(ScriptEntry::class.java)

    val automationPlugins: NamedDomainObjectContainer<PluginEntry> =
        objects.domainObjectContainer(PluginEntry::class.java)

    fun scripts(action: Action<NamedDomainObjectContainer<ScriptEntry>>) {
        action.execute(scripts)
    }

    fun automationPlugins(action: Action<NamedDomainObjectContainer<PluginEntry>>) {
        action.execute(automationPlugins)
    }
}
