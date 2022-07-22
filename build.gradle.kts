import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.dorongold.task-tree") version "2.1.0"
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
            substitute(module("plus.sourceplus.interface:interface-booster-ui"))
                .using(project(":interfaces:booster-ui"))
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}

tasks {
    register("buildDist") {
        //todo: use gradle copy task
        dependsOn("platform:core:shadowJar")
        doLast {
            file("dist/spp-platform-${project.version}/config").mkdirs()
            file("docker/e2e/config/spp-platform.yml")
                .copyTo(file("dist/spp-platform-${project.version}/config/spp-platform.yml"), true)
            file("platform/core/build/libs/spp-platform-${project.version}.jar")
                .copyTo(file("dist/spp-platform-${project.version}/spp-platform-${project.version}.jar"), true)
        }
    }
    register<Tar>("makeDist") {
        dependsOn(":buildDist")
        mustRunAfter(":buildDist")

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
