# tribot-dev-plugin

A Gradle plugin for developing Tribot automation scripts and plugins. It configures JDK 21,
the runtime dependency set, the script/plugin SDK stubs, and provides `fatJar` /
`deployLocally` tasks that produce deployable automation JARs.

## Quick start

Two copy-paste blocks. That's it.

### `settings.gradle.kts`

JitPack doesn't publish the Gradle plugin marker that maps the `org.tribot.dev` plugin
ID to its module coordinates, so we redirect the plugin request to the JitPack module
directly. Gradle then resolves whatever version string you passed (including the
`latest.release` dynamic selector) against JitPack's own `maven-metadata.xml`.

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.tribot.dev") {
                useModule("com.github.TribotRS.tribot-dev-plugin:plugin:${requested.version}")
            }
        }
    }
}

rootProject.name = "my-automations"
// include("my-script", "my-shared-library", ...)
```

### `build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.tribot.dev") version "latest.release"
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

- **`id("org.tribot.dev") version "latest.release"`** — Gradle's dynamic-version
  resolver reads JitPack's `maven-metadata.xml` for `com.github.TribotRS.tribot-dev-plugin:plugin`
  and picks the newest tagged release.
- **`automation-sdk` uses the same mechanism** — the plugin adds it as a
  `compileOnly` dependency with `com.github.TribotRS:automation-sdk:latest.release`,
  so every build pulls the newest SDK release transparently. You don't need to touch
  anything in your `build.gradle.kts` to pick it up.
- **Gradle caches dynamic-version resolutions for 24 hours by default.** A release
  published *after* your last resolve won't be seen until the cache entry expires or
  you explicitly refresh (see below).

### Why `latest.release` and not a pin?

Tribot Echo's runtime picks up new SDK releases as Tribot is updated. If your compiled
script targets a stale SDK version, you may hit method-not-found or signature mismatches
when the runtime is newer than what you compiled against. Using `latest.release` keeps
your compile-time API in lockstep with the runtime. If a new SDK release breaks your
script at compile time, you'll see the error locally before it ever reaches the
repo-compiler or the runtime.

If you need reproducible builds (for example, in CI or for a specific supported release
of Tribot), pin the plugin version explicitly: `id("org.tribot.dev") version "v1.0.5"`.

## Refreshing to a new release

Scripters usually run Gradle through IntelliJ, so here's what to do when you see a new
release you want to pull in right away.

### The one command that always works

```bash
./gradlew --refresh-dependencies
```

`--refresh-dependencies` bypasses Gradle's dynamic-version cache, re-fetches
`maven-metadata.xml` from JitPack, and re-resolves every dependency including the
plugin itself. After it finishes, hit Reload in IntelliJ's Gradle tool window.

### Passive refresh

You usually don't need to do anything. Gradle's dynamic-version cache expires after
24 hours, and Gradle daemons idle out after ~3 hours, so a fresh daemon on the next
sync will pick up whatever's latest automatically. The manual refresh above is only
for "I saw the release announcement 5 minutes ago and want it *now*" scenarios.

### First-time fetch of a brand-new release

JitPack lazily builds artifacts on first request, so the very first consumer to pull
a newly-tagged release eats the build time (usually under a minute — the build output
appears in your Gradle log). Subsequent users get it from JitPack's cache instantly.

### Tightening the cache window (optional)

If you want new releases to arrive faster *without* having to remember
`--refresh-dependencies`, drop the dynamic-version cache in your consumer
`build.gradle.kts`:

```kotlin
configurations.all {
    resolutionStrategy.cacheDynamicVersionsFor(10, "minutes")
}
```

This makes every dynamic-version resolution (`latest.release`, `+`, etc.) re-check
metadata every 10 minutes instead of every 24 hours. It costs one extra network
round-trip per configuration every 10 minutes — negligible for interactive use but
probably not what you want in CI.

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
| `zipSources` | Builds a source zip suitable for upload to the Tribot repo-compiler. Bundles this project's sources, its local project-dependency sources (walked transitively across `bundled`/`implementation`/`api`/`compileOnly`), and any non-source resources in those projects. Third-party jars and file dependencies are skipped — those have no source to bundle. Output: `build/libs/<project-name>-sources.zip`. Fails the build on any path collision between merged projects. |

Automations directory:

- macOS: `~/Library/Application Support/tribot/automations`
- Windows: `%APPDATA%/.tribot/automations`
- Linux: `~/.tribot/automations`

## Version drift

The plugin checks your Kotlin / Compose / JDK versions at configuration time and
**warns** if they don't match the versions Tribot's runtime expects. It never throws —
if you deliberately drift, it's your problem to resolve.
