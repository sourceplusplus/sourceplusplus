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
                artifactId = "platform-storage"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    compileOnly(project(":platform:common"))

    testImplementation(project(":platform:common"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-platform-storage")
    }
}
