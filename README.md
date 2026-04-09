# tribot-dev-plugin

A Gradle plugin for developing Tribot automation scripts and plugins. It configures JDK 21,
the runtime dependency set, the script/plugin SDK stubs, and provides `fatJar` /
`deployLocally` tasks that produce deployable automation JARs.

## Usage

Consumers declare the Kotlin JVM plugin and this plugin in their `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.tribot.dev") version "<version>"
}

dependencies {
    // Only use `bundled` for 3rd-party libraries that Tribot Echo does NOT provide
    // at runtime. Anything declared here will be packed into your fatJar. See the
    // "Declaring dependencies" section below for when to use this.
    // bundled("com.example:my-private-lib:1.0")
}

tribot {
    // Defaults:
    // useScriptSdk = true
    // useLegacyApi = false
    // useCompose   = true
    // useJavaFx    = true

    scripts {
        register("main") {
            className = "com.example.MyScript"
            scriptName = "My Script"   // display name shown in Tribot
            version = "1.0.0"
            author = "Me"
            description = "Does the thing"
            category = "Utility"
        }
    }

    automationPlugins {
        register("mainPlugin") {
            className = "com.example.MyPlugin"
            pluginName = "My Plugin"
            version = "1.0.0"
        }
    }
}
```

## Declaring dependencies

Tribot Echo already provides a large set of libraries on its runtime classpath —
`automation-sdk`, `script-sdk`, `legacy-api`, `runelite-client`, okhttp, gson, the
Apache Commons family, Guava, Caffeine, jfoenix, jdistlib, jfreechart, fx-gson,
discord-webhooks, JavaFX, and Compose Desktop. The plugin adds all of these as
`compileOnly` for you automatically when you apply it, so your script code can
import from them without ever listing them in your own `dependencies { }` block.

Because everything a script normally needs is already on the runtime classpath,
**the fatJar is opt-in about what it bundles**. If you declare nothing, your jar
contains exactly your compiled classes plus the generated `echo-scripts.json` /
`echo-plugins.json`. Nothing extra.

When you genuinely need a 3rd-party library that Tribot Echo does *not* provide,
declare it with `bundled(...)`:

```kotlin
dependencies {
    bundled("com.example:my-private-lib:1.0")
}
```

`bundled` is a Gradle configuration the plugin adds to every consumer project. It is:
- **on the compile classpath** (so you can `import` the library in your script code)
- **on the runtime classpath** (so `./gradlew test` or `gradle run` against the lib works locally)
- **packed into the fatJar** (so it's available to your script inside Tribot Echo at runtime)

### `bundled` vs `implementation` vs `compileOnly`

| Configuration | Visible at compile? | In fatJar? | When to use |
|---|---|---|---|
| `bundled`        | yes | **yes** | 3rd-party libs Tribot Echo does NOT provide |
| `implementation` | yes | no      | Rare — if you want a compile/test dep *without* bundling it (e.g. something already available in a related project that's not a fatJar) |
| `compileOnly`    | yes | no      | Already managed by the plugin — you should not need to declare these |
| `runtimeOnly`    | no  | no      | Not recommended; won't end up in the fatJar |

**Common footgun:** declaring a 3rd-party lib as `implementation("...")` instead of
`bundled("...")`. Gradle will happily compile your code, but at runtime inside
Tribot Echo the class will be missing and your script will fail with
`ClassNotFoundException`. If the lib is provided by Tribot, use `compileOnly`
(or just let the plugin handle it). If it's not, use `bundled`.

### Why `scriptName` / `pluginName` instead of `name`?

`ScriptEntry` and `PluginEntry` implement Gradle's `Named` interface so they can live
inside a `NamedDomainObjectContainer` (which is what lets you write `register("main") { ... }`).
`Named.getName()` is reserved by Gradle for the container key, so the display name is
exposed as `scriptName` / `pluginName`.

### Why `automationPlugins` instead of `plugins`?

`plugins` would collide with Gradle's top-level `plugins { }` block when used inside a
`tribot { }` configuration, so the container is named `automationPlugins`.

## Tasks

| Task | Description |
|------|-------------|
| `fatJar` | Builds a fat JAR containing your compiled classes, resources, and any libs declared via `bundled(...)`. Does NOT bundle anything from `implementation` / `runtimeClasspath` — see "Declaring dependencies" above. Kotlin stdlib and JetBrains annotations are always stripped. |
| `deployLocally` | Runs `fatJar` and copies the output into the platform-specific Tribot automations directory. |
| `generateManifest` | Writes `echo-scripts.json` / `echo-plugins.json` into the main resources, derived from the `tribot { }` DSL. Wired as a `processResources` dependency. |

Automations directory:

- macOS: `~/Library/Application Support/tribot/automations`
- Windows: `%APPDATA%/.tribot/automations`
- Linux: `~/.tribot/automations`

## Version drift

The plugin checks your Kotlin / Compose / JDK versions at configuration time and
**warns** if they don't match the versions Tribot's runtime expects. It never throws —
if you deliberately drift, it's your problem to resolve.
