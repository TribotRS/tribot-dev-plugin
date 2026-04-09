plugins {
    `maven-publish`
}

val stubJar = file("libs/legacy-api.jar")

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "legacy-api"
            version = project.version.toString()
            if (stubJar.exists() && stubJar.length() > 0) {
                artifact(stubJar)
            } else {
                logger.warn("legacy-api: ${stubJar.absolutePath} is missing — publishing pom only")
            }
            pom {
                name.set("Tribot Legacy API (stub)")
                description.set(
                    "Compile-only stub jar for the Tribot legacy API. " +
                        "The real implementation is provided at runtime by Tribot."
                )
            }
        }
    }
}
