package org.tribot.devplugin

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainSpec
import java.io.File

/**
 * Gradle plugin that configures a project for developing Tribot automation scripts and
 * plugins. See `README.md` for consumer-facing usage.
 *
 * This plugin intentionally does **not** auto-apply `org.jetbrains.kotlin.jvm` or the
 * Compose plugins — consumers declare those in their own `plugins { }` block. We *warn*
 * via `project.logger.warn` on version drift so consumers are free to deliberately
 * override pinned versions at their own risk.
 *
 * The `automation-sdk` dependency uses Gradle's `latest.release` dynamic version against
 * JitPack's `maven-metadata.xml`, so scripters pick up new SDK releases automatically
 * without needing to re-release this plugin every time.
 */
class TribotDevPlugin : Plugin<Project> {

    /**
     * The plugin's own published version, read from a META-INF resource generated during
     * the plugin's build. Used to construct self-referential coords for the stub modules
     * so a JitPack release at tag `v1.2.3` pulls in sibling stubs at the same tag.
     */
    private val pluginVersion: String by lazy {
        TribotDevPlugin::class.java
            .getResourceAsStream("/META-INF/tribot-dev-plugin.version")
            ?.bufferedReader()
            ?.use { it.readText().trim() }
            ?: "0.0.0-SNAPSHOT"
    }

    override fun apply(project: Project) {
        // Hook on Kotlin plugin being applied so we can verify its version reflectively.
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.afterEvaluate(object : Action<Project> {
                override fun execute(p: Project) = verifyKotlinVersion(p)
            })
        }

        project.pluginManager.apply("java")
        project.extensions.configure(
            JavaPluginExtension::class.java,
            object : Action<JavaPluginExtension> {
                override fun execute(java: JavaPluginExtension) {
                    java.sourceCompatibility = JavaVersion.VERSION_21
                    java.targetCompatibility = JavaVersion.VERSION_21
                    java.toolchain(object : Action<JavaToolchainSpec> {
                        override fun execute(tc: JavaToolchainSpec) {
                            tc.languageVersion.set(JavaLanguageVersion.of(ScriptDependencies.EXPECTED_JDK_VERSION))
                        }
                    })
                }
            }
        )

        project.repositories.mavenCentral()
        project.repositories.maven(object : Action<MavenArtifactRepository> {
            override fun execute(r: MavenArtifactRepository) {
                r.setUrl("https://repo.runelite.net")
            }
        })
        project.repositories.maven(object : Action<MavenArtifactRepository> {
            override fun execute(r: MavenArtifactRepository) {
                r.setUrl("https://jitpack.io")
            }
        })

        // `bundled` — the opt-in mechanism for declaring 3rd-party libs that the fatJar
        // should include. Use this instead of `implementation` for deps that are NOT
        // already provided at runtime by Tribot Echo. `implementation.extendsFrom(bundled)`
        // so these deps are also visible to the compiler and usable in local tests.
        val bundled = project.configurations.create("bundled", object : Action<Configuration> {
            override fun execute(cfg: Configuration) {
                cfg.isCanBeResolved = true
                cfg.isCanBeConsumed = false
                cfg.description =
                    "Third-party libraries to bundle into the Tribot automation fat jar. " +
                        "Use this instead of `implementation` for deps that are NOT " +
                        "provided at runtime by Tribot Echo."
            }
        })
        project.configurations.getByName("implementation").extendsFrom(bundled)

        val ext = project.extensions.create("tribot", TribotDevExtension::class.java)

        project.afterEvaluate(object : Action<Project> {
            override fun execute(proj: Project) {
                if (!proj.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
                    proj.logger.warn(
                        "tribot-dev-plugin: org.jetbrains.kotlin.jvm is not applied. " +
                            "Add `kotlin(\"jvm\") version \"${ScriptDependencies.EXPECTED_KOTLIN_VERSION}\"` " +
                            "to your plugins { } block."
                    )
                }
                configureAfterEvaluate(proj, ext)
            }
        })
    }

    private fun verifyKotlinVersion(project: Project) {
        val kotlinPlugin = project.plugins.findPlugin("org.jetbrains.kotlin.jvm") ?: return
        val method = runCatching {
            kotlinPlugin.javaClass.getMethod("getKotlinPluginVersion")
        }.getOrNull() ?: return
        val actual = runCatching { method.invoke(kotlinPlugin) as? String }.getOrNull() ?: return
        if (actual != ScriptDependencies.EXPECTED_KOTLIN_VERSION) {
            project.logger.warn(
                "tribot-dev-plugin: Kotlin version mismatch. Tribot runtime expects " +
                    "${ScriptDependencies.EXPECTED_KOTLIN_VERSION} but project has $actual. " +
                    "Pin `kotlin(\"jvm\") version \"${ScriptDependencies.EXPECTED_KOTLIN_VERSION}\"`."
            )
        }
    }

    private fun configureAfterEvaluate(project: Project, ext: TribotDevExtension) {
        val deps = project.dependencies

        for (coord in ScriptDependencies.list) {
            deps.add("compileOnly", coord)
        }
        deps.add("compileOnly", ScriptDependencies.RUNELITE_COORD)
        deps.add("compileOnly", ScriptDependencies.AUTOMATION_SDK_COORD)

        if (ext.useScriptSdk) {
            deps.add("compileOnly", "com.github.TribotRS.tribot-dev-plugin:script-sdk:$pluginVersion")
            deps.add("compileOnly", "com.github.TribotRS.tribot-dev-plugin:script-sdk-interfaces:$pluginVersion")
        }
        if (ext.useLegacyApi) {
            deps.add("compileOnly", "com.github.TribotRS.tribot-dev-plugin:legacy-api:$pluginVersion")
            deps.add("compileOnly", "com.github.TribotRS.tribot-dev-plugin:legacy-api-interfaces:$pluginVersion")
        }

        if (ext.useJavaFx) {
            val classifier = javafxClassifier()
            for (module in ScriptDependencies.javafxModules) {
                deps.add("compileOnly", "org.openjfx:$module:${ScriptDependencies.JAVAFX_VERSION}:$classifier")
            }
        }

        if (ext.useCompose) {
            val hasCompose = project.plugins.hasPlugin("org.jetbrains.compose")
            val hasComposeKotlin = project.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose")
            if (!hasCompose || !hasComposeKotlin) {
                project.logger.warn(
                    "tribot-dev-plugin: useCompose=true but Compose plugins are not applied. " +
                        "Add `id(\"org.jetbrains.compose\") version \"${ScriptDependencies.EXPECTED_COMPOSE_VERSION}\"` " +
                        "and `id(\"org.jetbrains.kotlin.plugin.compose\") version \"${ScriptDependencies.EXPECTED_KOTLIN_VERSION}\"` " +
                        "to your plugins { } block, or set tribot.useCompose = false."
                )
            } else {
                // Resolve `compose.desktop.currentOs` reflectively so we don't need a
                // compile-time dependency on the Compose Gradle plugin.
                val composeExt = project.extensions.findByName("compose")
                if (composeExt != null) {
                    runCatching {
                        val desktop = composeExt.javaClass.getMethod("getDesktop").invoke(composeExt)
                        val currentOs = desktop.javaClass.getMethod("getCurrentOs").invoke(desktop)
                        if (currentOs != null) {
                            deps.add("compileOnly", currentOs)
                        }
                    }.onFailure { e ->
                        project.logger.warn(
                            "tribot-dev-plugin: failed to wire compose.desktop.currentOs — ${e.message}"
                        )
                    }
                }
            }
        }

        registerBuildTasks(project, ext)
    }

    private fun registerBuildTasks(project: Project, ext: TribotDevExtension) {
        val scriptEntries = ext.scripts.toList().map { it.toMap() }
        val pluginEntries = ext.automationPlugins.toList().map { it.toMap() }

        // Library-style consumer — no scripts or plugins declared, so no manifest/fatJar/deploy wiring.
        if (scriptEntries.isEmpty() && pluginEntries.isEmpty()) {
            return
        }

        val scriptManifestJson: String? = if (scriptEntries.isNotEmpty()) {
            buildJsonManifest(
                rootKey = "scripts",
                entries = scriptEntries,
                fields = listOf("className", "name", "version", "author", "description", "category"),
            )
        } else null

        val pluginManifestJson: String? = if (pluginEntries.isNotEmpty()) {
            buildJsonManifest(
                rootKey = "plugins",
                entries = pluginEntries,
                fields = listOf("className", "name", "version"),
            )
        } else null

        // Generated manifest files live in a dedicated output directory rather than
        // being written straight into `build/resources/main` (which is owned by
        // processResources). Wiring that directory as a source-set resource srcDir
        // lets processResources pick the files up naturally, and gives generateManifest
        // an output directory it actually owns — so Gradle can invalidate and clean it
        // correctly between runs.
        val generatedResourcesDir = project.layout.buildDirectory.dir("generated/tribot-resources")

        project.extensions.getByType(SourceSetContainer::class.java)
            .getByName("main")
            .resources
            .srcDir(generatedResourcesDir)

        val generateManifest = project.tasks.register(
            "generateManifest",
            object : Action<Task> {
                override fun execute(task: Task) {
                    task.group = "tribot"
                    task.description = "Generates echo-scripts.json / echo-plugins.json from the tribot { } DSL"
                    // Register the manifest JSON strings as task inputs so edits to the
                    // `tribot { }` DSL (class name, script name, version, author, etc.)
                    // invalidate this task's up-to-date cache. Without this, Gradle would
                    // keep the previous manifest files and the only workaround was
                    // `gradle clean` before re-running `deployLocally`.
                    task.inputs.property("scriptManifestJson", scriptManifestJson ?: "")
                    task.inputs.property("pluginManifestJson", pluginManifestJson ?: "")
                    task.outputs.dir(generatedResourcesDir)
                    task.doLast(object : Action<Task> {
                        override fun execute(t: Task) {
                            val dir = generatedResourcesDir.get().asFile
                            // Wipe the directory so stale manifest files (e.g. from a
                            // previous run that had a script the user has since removed)
                            // don't linger in the packaged fatJar.
                            dir.deleteRecursively()
                            dir.mkdirs()
                            scriptManifestJson?.let { File(dir, "echo-scripts.json").writeText(it) }
                            pluginManifestJson?.let { File(dir, "echo-plugins.json").writeText(it) }
                        }
                    })
                }
            }
        )

        project.tasks.named("processResources").configure(object : Action<Task> {
            override fun execute(t: Task) {
                t.dependsOn(generateManifest)
            }
        })

        val fatJar = project.tasks.register(
            "fatJar",
            Jar::class.java,
            object : Action<Jar> {
                override fun execute(jar: Jar) {
                    jar.group = "tribot"
                    jar.description = "Builds a fat JAR with classes, resources, and runtime dependencies"
                    jar.dependsOn("classes", generateManifest)
                    jar.archiveBaseName.set(project.name)
                    jar.archiveVersion.set("")
                    jar.archiveClassifier.set("")

                    jar.exclude("META-INF/**")

                    jar.from(project.layout.buildDirectory.dir("classes/kotlin/main"))
                    jar.from(project.layout.buildDirectory.dir("classes/java/main"))
                    jar.from(project.layout.buildDirectory.dir("resources/main"))

                    // Bundle exactly — and only — the deps declared via the `bundled`
                    // configuration. Everything else the consumer might have on
                    // compile/runtime classpath (automation-sdk, script-sdk, runelite,
                    // JavaFX, Compose, the ScriptDependencies runtime libs) is assumed to
                    // be provided at runtime by Tribot Echo and is NOT bundled here.
                    //
                    // We still strip kotlin-stdlib / annotations- defensively in case a
                    // bundled lib pulls them in transitively — Kotlin's stdlib is always
                    // provided by the host classloader.
                    jar.from(
                        project.configurations.named("bundled").map { config: Configuration ->
                            config.filter { f ->
                                !f.name.startsWith("kotlin-stdlib") && !f.name.startsWith("annotations-")
                            }.map { if (it.isDirectory) it else project.zipTree(it) }
                        }
                    )

                    jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                }
            }
        )

        val tribotDir: File = run {
            val osName = System.getProperty("os.name")
            when {
                osName.contains("Mac") ->
                    File(System.getProperty("user.home") + "/Library/Application Support/tribot")
                osName.contains("Windows") ->
                    File(System.getenv("APPDATA") + "/.tribot")
                else ->
                    File(System.getProperty("user.home") + "/.tribot")
            }
        }
        val automationsDir = tribotDir.resolve("automations")

        project.tasks.register("deployLocally", object : Action<Task> {
            override fun execute(task: Task) {
                task.group = "tribot"
                task.description = "Builds fatJar and copies it to the local Tribot automations directory"
                task.dependsOn(fatJar)
                task.doLast(object : Action<Task> {
                    override fun execute(t: Task) {
                        val jarFile = fatJar.get().archiveFile.get().asFile
                        automationsDir.mkdirs()
                        if (jarFile.exists()) {
                            jarFile.copyTo(File(automationsDir, jarFile.name), overwrite = true)
                            project.logger.lifecycle("Deployed ${jarFile.name} -> ${automationsDir.absolutePath}")
                        } else {
                            throw GradleException("fatJar did not produce ${jarFile.absolutePath}")
                        }
                    }
                })
            }
        })

        registerZipSourcesTask(project)
    }

    /**
     * Registers a `zipSources` task that packages this project's sources + any local
     * project-dependency sources into a zip suitable for upload to the Tribot
     * repo-compiler.
     *
     * The repo-compiler extracts the uploaded zip directly into the `src/` directory
     * of a fixed Gradle template, whose `sourceSets.main` treats `src/` as the root for
     * both java/kotlin sources AND resources (everything not ending in .java/.kt is a
     * resource). This means the zip must contain entries at their classpath-relative
     * paths — e.g. `scripts/myScript/MyScript.kt` and `scripts/myScript/config.json`.
     * Gradle's [SourceDirectorySet.allSource] produces exactly that layout when added
     * to a [Zip] task's `from()`, so we just hand it off.
     *
     * Project dependencies on other local Gradle subprojects (typically declared via
     * `bundled(project(":shared-library"))` or `implementation(project(...))`) get
     * their sources merged into the same zip, walking transitively. Third-party deps
     * and prebuilt file deps are skipped — their sources aren't available and the
     * scripter is expected to either rely on `compileOnly` SDK-provided libs or
     * re-vendor anything else they need as an in-repo project.
     *
     * Uses [DuplicatesStrategy.FAIL] so a path collision between merged projects
     * fails the build loudly instead of silently dropping a file.
     */
    private fun registerZipSourcesTask(project: Project) {
        project.tasks.register("zipSources", Zip::class.java, object : Action<Zip> {
            override fun execute(zip: Zip) {
                zip.group = "tribot"
                zip.description =
                    "Packages source code (this project + local project deps + resources) " +
                        "into a zip suitable for upload to the Tribot repo-compiler."
                zip.archiveBaseName.set(project.name)
                zip.archiveClassifier.set("sources")
                zip.archiveExtension.set("zip")
                zip.archiveVersion.set("")
                zip.destinationDirectory.set(project.layout.buildDirectory.dir("libs"))

                // Path collisions between the origin project and a bundled library
                // indicate a real problem — fail fast rather than silently overwriting.
                zip.duplicatesStrategy = DuplicatesStrategy.FAIL

                // Lazy provider: evaluated at task execution time, by which point all
                // participating subprojects are guaranteed to be evaluated.
                zip.from(
                    project.provider {
                        collectSourcesForZip(project)
                    }
                )
            }
        })
    }

    /**
     * Collects [org.gradle.api.file.SourceDirectorySet] instances from the origin
     * project and every transitively-reachable local project dependency, in BFS order
     * with deduplication. Returns a flat list that [Zip.from] can consume.
     *
     * The walk covers `bundled`, `implementation`, `api`, and `compileOnly`
     * configurations, which together cover everything a script might legitimately
     * reference at compile time.
     */
    private fun collectSourcesForZip(originProject: Project): List<Any> {
        val sources = mutableListOf<Any>()

        originProject.extensions.findByType(SourceSetContainer::class.java)
            ?.findByName("main")?.allSource
            ?.let { sources.add(it) }

        val relevantConfigs = listOf("bundled", "implementation", "api", "compileOnly")
        val originPath = projectPath(originProject)
        val visited = mutableSetOf(originPath)
        val queue = ArrayDeque<Project>()
        queue.addLast(originProject)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (configName in relevantConfigs) {
                val config = current.configurations.findByName(configName) ?: continue
                for (dep in config.allDependencies) {
                    if (dep !is ProjectDependency) continue
                    val depPath = projectDependencyPath(dep) ?: continue
                    if (!visited.add(depPath)) continue
                    val depProject = originProject.rootProject.findProject(depPath) ?: continue
                    depProject.extensions.findByType(SourceSetContainer::class.java)
                        ?.findByName("main")?.allSource
                        ?.let { sources.add(it) }
                    queue.addLast(depProject)
                }
            }
        }

        return sources
    }

    /**
     * Resolves a [ProjectDependency] to its colon-path form (e.g. `":test-library"`)
     * across Gradle versions. Gradle 8.11 introduced [ProjectDependency.getPath]
     * directly; Gradle 9.0 removed the older `getDependencyProject()` approach.
     * Reflection keeps the plugin source-compatible with both the 8.x and 9.x APIs.
     */
    private fun projectDependencyPath(dep: ProjectDependency): String? {
        // Gradle 8.11+ and Gradle 9.x: `ProjectDependency.getPath()`
        val viaGetPath = runCatching {
            dep.javaClass.getMethod("getPath").invoke(dep) as? String
        }.getOrNull()
        if (viaGetPath != null) return viaGetPath

        // Gradle <= 8.10: fall back to `getDependencyProject().getPath()`
        return runCatching {
            val depProject = dep.javaClass.getMethod("getDependencyProject").invoke(dep)
                ?: return@runCatching null
            depProject.javaClass.getMethod("getPath").invoke(depProject) as? String
        }.getOrNull()
    }

    /**
     * Gets a [Project]'s colon-path. Stable across Gradle versions but wrapped here
     * for symmetry with [projectDependencyPath].
     */
    private fun projectPath(project: Project): String = project.path

    private fun javafxClassifier(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isAarch64 = arch == "aarch64" || arch == "arm64"
        return when {
            os.contains("mac") -> if (isAarch64) "mac-aarch64" else "mac"
            os.contains("win") -> if (isAarch64) "win-aarch64" else "win"
            else -> if (isAarch64) "linux-aarch64" else "linux"
        }
    }

    private fun buildJsonManifest(
        rootKey: String,
        entries: List<Map<String, String>>,
        fields: List<String>,
    ): String {
        val array = entries.joinToString(",\n") { entry ->
            val props = fields.joinToString(",\n") { field ->
                "            \"$field\": \"${entry[field].orEmpty().jsonEscape()}\""
            }
            "        {\n$props\n        }"
        }
        return "{\n    \"$rootKey\": [\n$array\n    ]\n}"
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
