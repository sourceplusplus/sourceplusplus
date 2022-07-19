plugins {
    kotlin("jvm")
    id("maven-publish")
}

val processorGroup: String by project
val projectVersion: String by project

group = processorGroup
version = project.properties["processorVersion"] as String? ?: projectVersion

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
    maven(url = "https://pkg.sourceplus.plus/sourceplusplus/protocol")
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/processor-live-instrument")
            credentials {
                username = System.getenv("GH_PUBLISH_USERNAME")?.toString()
                password = System.getenv("GH_PUBLISH_TOKEN")?.toString()
            }
        }
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = processorGroup
                artifactId = "processor-live-instrument"
                version = project.version.toString()

                artifact("$buildDir/libs/spp-processor-live-instrument-${project.version}.jar")
            }
        }
    }
}

dependencies {
    compileOnly(project(":platform:common"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-instrument")
    }
}
