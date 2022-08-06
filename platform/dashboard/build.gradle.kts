plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("com.github.johnrengelman.shadow")
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
                artifactId = "platform-dashboard"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    compileOnly(project(":platform:common"))
    compileOnly(project(":platform:storage"))
    implementation(project(":interfaces:booster-ui"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("spp-live-dashboard")
        archiveClassifier.set("")

        include("META-INF/**")
        include("spp/**")

        //include webroot/**
        include("webroot/**")
        include {
            if (it.toString().contains("PatternSetSnapshottingFilter")) {
                true
            } else {
                it.name.contains("booster-ui")
            }
        }

        configurations.add(project.configurations.compileClasspath.get())
        configurations.add(project.configurations.runtimeClasspath.get())
        configurations.add(project.configurations.shadow.get())
    }
    getByName("jar").dependsOn("shadowJar")
}
