plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/live-platform")
            credentials {
                username = System.getenv("GH_PUBLISH_USERNAME")?.toString()
                password = System.getenv("GH_PUBLISH_TOKEN")?.toString()
            }
        }
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = platformGroup
                artifactId = "platform-bridge"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:common"))
}
