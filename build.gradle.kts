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
// a git tag `v<version>` and a GitHub release, which JitPack picks up automatically to
// publish the artifacts under com.github.TribotRS.tribot-dev-plugin:<module>:v<version>.
//
// Versioning:
//   - By default, fetches the latest release from GitHub and bumps the patch component.
//   - `-PreleaseType=minor` bumps minor (resets patch to 0).
//   - `-PreleaseType=major` bumps major (resets minor and patch to 0).
//   - `-PreleaseVersion=x.y.z` overrides auto-bump with an explicit version. Use this for
//     the very first release, or to skip ahead.
//
// Prerequisites:
//   - The `gh` CLI must be installed and authenticated against the TribotRS org.
//   - The monorepo's tribot-dev-plugin subtree must already be pushed to the public repo's
//     main branch (this task does NOT push code — see the monorepo README for how).
//
// Usage:
//   ./gradlew release                                # auto-bump patch
//   ./gradlew release -PreleaseType=minor            # auto-bump minor
//   ./gradlew release -PreleaseType=major            # auto-bump major
//   ./gradlew release -PreleaseVersion=0.1.0         # explicit (required for first release)
tasks.register("release") {
    group = "tribot"
    description = "Triggers a GitHub release workflow for tribot-dev-plugin on the public repo"

    doLast {
        val releaseVersion = resolveNextVersion(project, "TribotRS/tribot-dev-plugin")

        val cmd = listOf(
            "gh", "workflow", "run", "release.yml",
            "--repo", "TribotRS/tribot-dev-plugin",
            "--ref", "main",
            "-f", "version=$releaseVersion",
        )

        logger.lifecycle("Cutting release v$releaseVersion")
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

/**
 * Determines the next release version for the given `org/repo` on GitHub.
 *
 * Resolution order:
 *  1. `-PreleaseVersion=x.y.z` — explicit override (validated as semver).
 *  2. Fetch the latest release tag via `gh api repos/<repo>/releases/latest --jq .tag_name`,
 *     strip the `v` prefix, parse `<major>.<minor>.<patch>`, and bump according to
 *     `-PreleaseType=patch|minor|major` (default: `patch`).
 *
 * Errors with a helpful message if the GH API call fails or returns nothing (typical
 * reason: no releases exist yet — the user must pass `-PreleaseVersion=x.y.z` for the
 * first release).
 */
fun resolveNextVersion(project: Project, repo: String): String {
    val semverRegex = Regex("""^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$""")

    val override = (project.findProperty("releaseVersion") as? String)?.takeIf { it.isNotBlank() }
    if (override != null) {
        require(override.matches(semverRegex)) {
            "releaseVersion must look like 1.2.3 or 1.2.3-rc1, got: $override"
        }
        return override
    }

    val bumpType = (project.findProperty("releaseType") as? String) ?: "patch"
    require(bumpType in setOf("patch", "minor", "major")) {
        "releaseType must be patch, minor, or major (got: $bumpType)"
    }

    val latestTag: String? = runCatching {
        val p = ProcessBuilder("gh", "api", "repos/$repo/releases/latest", "--jq", ".tag_name")
            .redirectErrorStream(false)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotBlank()) out else null
    }.getOrNull()

    if (latestTag == null) {
        error(
            "Could not determine the latest release for $repo. If this is the first release, " +
                "specify the version explicitly:\n" +
                "  ./gradlew release -PreleaseVersion=0.1.0\n" +
                "Otherwise, ensure the `gh` CLI is installed and authenticated."
        )
    }

    val stripped = latestTag.removePrefix("v")
    val parts = stripped.split(".")
    require(parts.size >= 3) { "Unable to parse semver from tag '$latestTag'" }
    val major = parts[0].toIntOrNull() ?: error("Invalid major component in tag '$latestTag'")
    val minor = parts[1].toIntOrNull() ?: error("Invalid minor component in tag '$latestTag'")
    val patch = parts[2].substringBefore("-").toIntOrNull()
        ?: error("Invalid patch component in tag '$latestTag'")

    val bumped = when (bumpType) {
        "major" -> "${major + 1}.0.0"
        "minor" -> "$major.${minor + 1}.0"
        else -> "$major.$minor.${patch + 1}"
    }

    project.logger.lifecycle("Previous release on $repo: v$stripped → bumping $bumpType → v$bumped")
    return bumped
}
