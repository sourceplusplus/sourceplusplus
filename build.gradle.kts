import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.dorongold.task-tree") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.noarg") apply false
}

val platformVersion: String by project
val skywalkingVersion: String by project

version = platformVersion

subprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.github.sourceplusplus.protocol:protocol"))
                .using(project(":protocol"))
            substitute(module("com.github.sourceplusplus.interface-portal:portal-jvm"))
                .using(project(":interfaces:portal"))
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}

tasks {
    register("makeDist") {
        //todo: use gradle copy task
        dependsOn(":platform:core:build", ":interfaces:marker:buildPlugin")
        doLast {
            file("dist/spp-platform-$version/config").mkdirs()
            file("config/spp-platform.yml")
                .copyTo(file("dist/spp-platform-$version/config/spp-platform.yml"))
            file("platform/core/build/graal/spp-platform")
                .copyTo(file("dist/spp-platform-$version/spp-platform"))
        }
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
