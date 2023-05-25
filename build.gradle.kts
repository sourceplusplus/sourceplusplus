import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.dorongold.task-tree") version "2.1.1"
    id("org.jetbrains.kotlin.plugin.noarg") apply false
    id("com.asarkar.gradle.build-time-tracker") version "4.3.0"
}

val projectVersion: String by project
val skywalkingVersion: String by project

version = project.properties["platformVersion"] as String? ?: projectVersion

subprojects {
    rootProject.properties.forEach {
        if (it.key.endsWith("Version") && it.value != null) {
            project.ext.set(it.key, it.value)
        }
    }

    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("plus.sourceplus:protocol"))
                .using(project(":protocol"))

            substitute(module("plus.sourceplus.interface:jetbrains-core"))
                .using(project(":interfaces:jetbrains:core"))
            substitute(module("plus.sourceplus.interface:jetbrains-marker"))
                .using(project(":interfaces:jetbrains:marker"))
            substitute(module("plus.sourceplus.interface:jetbrains-marker-jvm"))
                .using(project(":interfaces:jetbrains:marker:jvm-marker"))
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}

tasks {
    register<Copy>("assembleDist") {
        dependsOn("platform:assemble")

        into("dist/spp-platform-${project.version}")

        from(file("docker/e2e/config/spp-platform.yml")){
            into("config")
        }
        from(
            file("platform/core/build/libs/spp-platform-core-${project.version}.jar"),
            file("platform/bridge/build/libs/spp-platform-bridge-${project.version}.jar"),
            file("platform/storage/build/libs/spp-platform-storage-${project.version}.jar"),
            file("platform/processor/live-instrument/build/libs/spp-live-instrument-${project.version}.jar"),
            file("platform/processor/live-view/build/libs/spp-live-view-${project.version}.jar")
        )
    }
    register<Tar>("makeDist") {
        dependsOn("assembleDist")

        into("spp-platform-${project.version}") {
            from("dist/spp-platform-${project.version}")
        }
        destinationDirectory.set(file("dist"))
        compression = Compression.GZIP
        archiveFileName.set("spp-platform-${project.version}.tar.gz")
    }

    register("downloadSkywalking") {
        doLast {
            val f = File(projectDir, "docker/e2e/apache-skywalking-apm-$skywalkingVersion.tar.gz")
            if (!f.exists()) {
                println("Downloading Apache SkyWalking")
                URL("https://downloads.apache.org/skywalking/$skywalkingVersion/apache-skywalking-apm-$skywalkingVersion.tar.gz")
                    .openStream().use { input ->
                        FileOutputStream(f).use { output ->
                            input.copyTo(output)
                        }
                    }
                println("Downloaded Apache SkyWalking")
            }
        }
    }
}
