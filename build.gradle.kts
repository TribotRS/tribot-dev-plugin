import java.io.ByteArrayOutputStream

// Resolve the published version in this order:
//   1. `-Pversion=x.y.z` CLI arg (used by JitPack when building a tag)
//   2. `-Ptribot.dev.plugin.version=x.y.z` CLI arg (escape hatch for local reproductions)
//   3. `git describe --tags --exact-match HEAD` (matches when the working tree is at a tagged commit)
//   4. fallback to `0.0.0-SNAPSHOT` for local development
val resolvedVersion: String = run {
    val cliVersion = (findProperty("version") as? String)
        ?.takeIf { it.isNotBlank() && it != "unspecified" }
    if (cliVersion != null) return@run cliVersion

    val customVersion = (findProperty("tribot.dev.plugin.version") as? String)
        ?.takeIf { it.isNotBlank() }
    if (customVersion != null) return@run customVersion

    val gitTag = runCatching {
        val out = ByteArrayOutputStream()
        val p = ProcessBuilder("git", "describe", "--tags", "--exact-match", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        p.inputStream.copyTo(out)
        if (p.waitFor() == 0) out.toString().trim() else null
    }.getOrNull()
    if (!gitTag.isNullOrBlank()) return@run gitTag

    "0.0.0-SNAPSHOT"
}

allprojects {
    group = "com.github.TribotRS.tribot-dev-plugin"
    version = resolvedVersion

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.runelite.net")
        maven("https://jitpack.io")
    }
}

// ─── Release task ───
//
// Triggers the GitHub release workflow on TribotRS/tribot-dev-plugin. The workflow creates
// a git tag `v<releaseVersion>` and a GitHub release, which JitPack picks up automatically
// to publish the artifacts under com.github.TribotRS.tribot-dev-plugin:<module>:v<version>.
//
// Prerequisites:
//   - The `gh` CLI must be installed and authenticated against the TribotRS org.
//   - The monorepo's tribot-dev-plugin subtree must already be pushed to the public repo's
//     main branch (this task does NOT push code).
//
// Usage:
//   ./gradlew release -PreleaseVersion=1.2.3
tasks.register("release") {
    group = "tribot"
    description = "Triggers a GitHub release workflow for tribot-dev-plugin on the public repo"

    doLast {
        val releaseVersion = (project.findProperty("releaseVersion") as? String)
            ?: error("Must specify release version: ./gradlew release -PreleaseVersion=1.2.3")

        require(releaseVersion.matches(Regex("""^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$"""))) {
            "releaseVersion must look like 1.2.3 or 1.2.3-rc1, got: $releaseVersion"
        }

        val cmd = listOf(
            "gh", "workflow", "run", "release.yml",
            "--repo", "TribotRS/tribot-dev-plugin",
            "--ref", "main",
            "-f", "version=$releaseVersion",
        )

        logger.lifecycle("Running: ${cmd.joinToString(" ")}")

        val process = ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (output.isNotBlank()) logger.lifecycle(output.trim())
        if (exit != 0) {
            error("gh workflow run failed with exit $exit — is the gh CLI authenticated and is the main branch pushed?")
        }

        logger.lifecycle("Triggered tribot-dev-plugin release for v$releaseVersion. Check progress at:")
        logger.lifecycle("  https://github.com/TribotRS/tribot-dev-plugin/actions")
    }
}
