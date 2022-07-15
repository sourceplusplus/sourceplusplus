plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project
val vertxVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = platformGroup
                artifactId = "platform-storage"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-redis-client:$vertxVersion")
}
