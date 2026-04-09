plugins {
    `maven-publish`
}

val stubJar = file("libs/script-sdk-interfaces.jar")

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "script-sdk-interfaces"
            version = project.version.toString()
            if (stubJar.exists() && stubJar.length() > 0) {
                artifact(stubJar)
            } else {
                logger.warn("script-sdk-interfaces: ${stubJar.absolutePath} is missing — publishing pom only")
            }
            pom {
                name.set("Tribot Script SDK Interfaces (stub)")
                description.set(
                    "Compile-only stub jar for the Tribot script SDK interface surface. " +
                        "The real implementation is provided at runtime by Tribot."
                )
            }
        }
    }
}
