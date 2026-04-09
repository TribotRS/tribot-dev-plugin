pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "tribot-dev-plugin"

include("plugin")
include("legacy-api")
include("legacy-api-interfaces")
include("script-sdk")
include("script-sdk-interfaces")
