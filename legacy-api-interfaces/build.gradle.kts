plugins {
    `maven-publish`
}

val stubJar = file("libs/legacy-api-interfaces.jar")

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "legacy-api-interfaces"
            version = project.version.toString()
            if (stubJar.exists() && stubJar.length() > 0) {
                artifact(stubJar)
            } else {
                logger.warn("legacy-api-interfaces: ${stubJar.absolutePath} is missing — publishing pom only")
            }
            pom {
                name.set("Tribot Legacy API Interfaces (stub)")
                description.set(
                    "Compile-only stub jar for the Tribot legacy API interface surface. " +
                        "The real implementation is provided at runtime by Tribot."
                )
            }
        }
    }
}
