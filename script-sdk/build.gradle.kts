plugins {
    `maven-publish`
}

val stubJar = file("libs/script-sdk.jar")

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "script-sdk"
            version = project.version.toString()
            if (stubJar.exists() && stubJar.length() > 0) {
                artifact(stubJar)
            } else {
                logger.warn("script-sdk: ${stubJar.absolutePath} is missing — publishing pom only")
            }
            pom {
                name.set("Tribot Script SDK (stub)")
                description.set(
                    "Compile-only stub jar for the Tribot modern script SDK. " +
                        "The real implementation is provided at runtime by Tribot."
                )
            }
        }
    }
}
