import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.avast.gradle.docker-compose")
}

val platformVersion: String by project
val skywalkingVersion: String by project

version = platformVersion

subprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.github.sourceplusplus.protocol:protocol")).using(project(":protocol"))
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
        dependsOn(":platform:build", ":processor:build", ":interfaces:marker:buildPlugin")
        doLast {
            file("dist/spp-platform-$version/config").mkdirs()
            file("platform/config/spp-platform.yml")
                .copyTo(file("dist/spp-platform-$version/config/spp-platform.yml"))
            file("platform/build/graal/spp-platform")
                .copyTo(file("dist/spp-platform-$version/spp-platform"))
            file("interfaces/cli/build/graal/spp-cli")
                .copyTo(file("dist/spp-platform-$version/spp-cli"))
            file("processor/build/libs/spp-processor-$version-shadow.jar")
                .copyTo(file("dist/spp-processor-$version.jar"))
            file("interfaces/marker/build/spp-plugin-$version.zip")
                .copyTo(file("dist/spp-plugin-$version.zip"))
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

dockerCompose {
    dockerComposeWorkingDirectory.set(File("./docker/e2e"))
    removeVolumes.set(true)
    waitForTcpPorts.set(false)

    if (System.getProperty("build.profile") == "debian") {
        useComposeFiles.set(listOf("docker-compose-debian.yml"))
    } else {
        useComposeFiles.set(listOf("docker-compose-jvm.yml"))
    }
}
