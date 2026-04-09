package org.tribot.devplugin

/**
 * Single source of truth for the third-party dependencies that Tribot's runtime
 * provides to scripts and plugins.
 *
 * The [list] must stay in lockstep with
 * `client/tribot-echo/buildSrc/src/main/kotlin/org/tribot/plugin/ScriptDependencies.kt`
 * in the Tribot monorepo — whenever echo's runtime classpath changes, this list must be
 * updated and `tribot-dev-plugin` re-released.
 */
internal object ScriptDependencies {
    val list = listOf(
        "com.squareup.okhttp3:okhttp:4.9.2",
        "com.google.code.gson:gson:2.8.6",
        "commons-io:commons-io:2.6",
        "org.apache.commons:commons-lang3:3.17.0",
        "org.apache.commons:commons-math3:3.6.1",
        "org.apache.commons:commons-collections4:4.2",
        "org.apache.commons:commons-configuration2:2.3",
        "commons-codec:commons-codec:1.11",
        "com.jfoenix:jfoenix:9.0.10",
        "com.google.guava:guava:26.0-jre",
        "net.sourceforge.jdistlib:jdistlib:0.4.5",
        "org.jfree:jfreechart:1.5.0",
        "com.github.ben-manes.caffeine:caffeine:3.0.2",
        "org.hildan.fxgson:fx-gson:4.0.1",
        "club.minnced:discord-webhooks:0.8.0",
    )

    /**
     * The JavaFX modules that Tribot's launcher passes via `--add-modules`.
     * Mirrors `client/tribot-echo/echo-launcher/src/main/kotlin/org/tribot/echo/launcher/Main.kt:102`.
     */
    val javafxModules = listOf(
        "javafx-base",
        "javafx-controls",
        "javafx-fxml",
        "javafx-graphics",
        "javafx-swing",
        "javafx-media",
        "javafx-web",
    )

    const val JAVAFX_VERSION = "21"

    // RuneLite's Maven repo (repo.runelite.net) exposes maven-metadata.xml, so
    // `latest.release` resolves there.
    const val RUNELITE_COORD = "net.runelite:client:latest.release"

    // JitPack does NOT expose maven-metadata.xml, so `latest.release` does not work.
    // Instead, the plugin queries the GitHub Releases API at configuration time to
    // resolve the latest tag for automation-sdk, falling back to the pinned fallback
    // version below if the API is unreachable. Bump the fallback whenever automation-sdk
    // cuts a new release that should be the last-known-good offline target.
    const val AUTOMATION_SDK_GROUP_ARTIFACT = "com.github.TribotRS:automation-sdk"
    const val AUTOMATION_SDK_REPO = "TribotRS/automation-sdk"
    const val AUTOMATION_SDK_FALLBACK_VERSION = "v1.0.9"

    // Self-check: the plugin also queries this repo's latest release and warns the
    // consumer if they're running an older version. Only used for the warning, never
    // used to resolve the running plugin (the plugin is what's RUNNING this code).
    const val PLUGIN_REPO = "TribotRS/tribot-dev-plugin"

    const val EXPECTED_KOTLIN_VERSION = "2.1.21"
    const val EXPECTED_COMPOSE_VERSION = "1.8.0"
    const val EXPECTED_JDK_VERSION = 21
}
