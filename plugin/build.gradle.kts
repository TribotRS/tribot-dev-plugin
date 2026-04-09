plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("tribotDev") {
            id = "org.tribot.dev"
            implementationClass = "org.tribot.devplugin.TribotDevPlugin"
            displayName = "Tribot Dev Plugin"
            description = "Configures a Gradle project for developing Tribot automation scripts and plugins."
        }
    }
}

// Generate a properties file with our own version so runtime plugin code can build
// self-referential coords like com.github.TribotRS.tribot-dev-plugin:script-sdk:<version>.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    outputs.dir(outputDir)
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    doLast {
        val dir = outputDir.get().asFile.resolve("META-INF")
        dir.mkdirs()
        dir.resolve("tribot-dev-plugin.version").writeText(versionString)
    }
}

sourceSets {
    main { resources { srcDir(generateVersionResource.map { it.outputs.files }) } }
}

tasks.named("processResources") { dependsOn(generateVersionResource) }

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            if (name == "pluginMaven") {
                artifactId = "plugin"
            }
        }
    }
}
