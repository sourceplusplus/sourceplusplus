plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("kotlin-kapt")
}

val platformGroup: String by project
val projectVersion: String by project
val vertxVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/sourceplusplus")
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
                artifactId = "platform-common"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    kapt("io.vertx:vertx-codegen:$vertxVersion:processor")
    compileOnly("io.vertx:vertx-codegen:$vertxVersion")
}
