# tribot-dev-plugin

A Gradle plugin for developing Tribot automation scripts and plugins. It configures JDK 21,
the runtime dependency set, the script/plugin SDK stubs, and provides `fatJar` /
`deployLocally` tasks that produce deployable automation JARs.

## Quick start

Two copy-paste blocks. That's it.

### `settings.gradle.kts`

The plugin is published through JitPack, which doesn't expose Maven metadata, so we need
an `eachPlugin` block that queries the GitHub Releases API for the latest tag. Keep this
at the top of your `settings.gradle.kts` and you can write `version "latest"` anywhere
else (or pin a specific version like `"v1.0.5"`).

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://jitpack.io")
    }

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.tribot.dev") {
                val version = if (requested.version == "latest" || requested.version.isNullOrBlank()) {
                    // Query GitHub for the latest tribot-dev-plugin release and cache the
                    // result for this Gradle invocation. Falls back to the pinned version
                    // below when offline or rate-limited.
                    resolveLatestTribotDevPluginVersion()
                } else {
                    requested.version!!
                }
                useModule("com.github.TribotRS.tribot-dev-plugin:plugin:$version")
            }
        }
    }
}

/**
 * Queries api.github.com for the latest TribotRS/tribot-dev-plugin release tag.
 * Bump `fallback` occasionally to match a known-good offline target.
 */
fun resolveLatestTribotDevPluginVersion(): String {
    val fallback = "v1.0.2"
    return runCatching {
        val url = java.net.URI.create(
            "https://api.github.com/repos/TribotRS/tribot-dev-plugin/releases/latest"
        ).toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "tribot-dev-plugin-bootstrap")
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull() ?: fallback
}

rootProject.name = "my-automations"
// include("my-script", "my-shared-library", ...)
```

### `build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.tribot.dev") version "latest"
}

repositories {
    mavenCentral()
    maven("https://repo.runelite.net")
    maven("https://jitpack.io")
}

tribot {
    // Defaults:
    // useScriptSdk = true
    // useLegacyApi = false
    // useCompose   = true    (requires you to also apply org.jetbrains.compose)
    // useJavaFx    = true

    scripts {
        register("main") {
            className = "com.example.MyScript"
            scriptName = "My Script"       // display name shown in Tribot
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

dependencies {
    // Use `bundled` for 3rd-party libs Tribot Echo does NOT provide at runtime.
    // See the "Declaring dependencies" section below. Leave this commented out
    // if you don't need anything extra — the plugin wires everything Tribot
    // provides as `compileOnly` automatically.
    // bundled("com.example:my-private-lib:1.0")
}
```

Now run `./gradlew deployLocally` and your script lands in your local Tribot
automations directory. **That's the whole setup.**

## Auto-update behavior

- **`id("org.tribot.dev") version "latest"`** — the `eachPlugin` block in your
  `settings.gradle.kts` queries GitHub on each Gradle configure and pulls in the newest
  tribot-dev-plugin release.
- **automation-sdk is resolved the same way at plugin apply time** — every time your
  script builds, the plugin asks GitHub what the latest `TribotRS/automation-sdk` release
  is and uses that exact tag. You never have to touch anything in your `build.gradle.kts`
  to pick up a new SDK release.
- **Both fall back to a pinned version when the GitHub API is unreachable** (offline,
  rate-limited, proxied, etc.), so your offline builds still work.
- **The plugin logs its resolved SDK version** at configure time so you can see exactly
  what it's pulling in:
  ```
  > Configure project :
  tribot-dev-plugin: resolved automation-sdk → v1.0.12 (latest from TribotRS/automation-sdk)
  ```
- **If you pin a specific plugin version and it goes stale,** the plugin warns you at
  configure time and tells you the latest release number, so you know when to bump.

### Why "latest" and not a pin?

Tribot Echo's runtime picks up new SDK releases as Tribot is updated. If your compiled
script targets a stale SDK version, you may hit method-not-found or signature mismatches
when the runtime is newer than what you compiled against. Using `"latest"` keeps your
compile-time API in lockstep with the runtime. If a new SDK release breaks your script
at compile time, you'll see the error locally before it ever reaches the repo-compiler
or the runtime.

If you need reproducible builds (for example, in CI or for a specific supported release
of Tribot), pin the plugin version explicitly: `id("org.tribot.dev") version "v1.0.5"`.

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
