import java.io.FileOutputStream
import java.net.URL

plugins {
    id("java")
    id("com.avast.gradle.docker-compose") version "0.14.3"

    val kotlinVersion = "1.5.0"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("js") version kotlinVersion apply false

    id("io.gitlab.arturbosch.detekt") version "1.18.1"
}

val platformGroup: String by project
val platformVersion: String by project
val skywalkingVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project

group = platformGroup
version = platformVersion

repositories {
    mavenCentral()
    jcenter()
    maven(url = "https://kotlin.bintray.com/kotlinx/")
}

subprojects {
    repositories {
        mavenCentral()
        jcenter()
        maven(url = "https://kotlin.bintray.com/kotlinx/")
        maven(url = "https://jitpack.io")
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    if (name == "control" || name == "services" || name == "protocol") return@subprojects

    apply(plugin = "kotlin-kapt")

    dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.1")
        implementation("io.vertx:vertx-core:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
        implementation("io.vertx:vertx-web:$vertxVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
        implementation("io.dropwizard.metrics:metrics-core:4.2.3")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.2.1")
    }

    apply<io.gitlab.arturbosch.detekt.DetektPlugin>()
    tasks {
        withType<io.gitlab.arturbosch.detekt.Detekt> {
            parallel = true
            buildUponDefaultConfig = true
        }

        withType<JavaCompile> {
            sourceCompatibility = "1.8"
            targetCompatibility = "1.8"
        }
        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions.apiVersion = "1.4"
            kotlinOptions.jvmTarget = "1.8"
            kotlinOptions.freeCompilerArgs +=
                listOf(
                    "-Xno-optimized-callable-references",
                    "-Xjvm-default=compatibility"
                )
        }

        withType<Test> {
            testLogging {
                events("passed", "skipped", "failed")
                setExceptionFormat("full")

                outputs.upToDateWhen { false }
                showStandardStreams = true
            }
        }
    }
}

tasks {
    register("makeDist") {
        //todo: use gradle copy task
        dependsOn(
            ":platform:build", ":probe:control:build", ":processor:build", ":interfaces:marker:buildPlugin"
        )
        doLast {
            file("dist/spp-platform-$version/config").mkdirs()
            file("dist/spp-platform-$version/probe").mkdirs()
            file("probe/control/build/libs/spp-probe-$version.jar")
                .copyTo(file("dist/spp-platform-$version/probe/spp-probe-$version.jar"))
            file("platform/config/spp-platform.yml")
                .copyTo(file("dist/spp-platform-$version/config/spp-platform.yml"))
            file("platform/build/graal/spp-platform")
                .copyTo(file("dist/spp-platform-$version/spp-platform"))
            file("interfaces/cli/build/graal/spp-cli")
                .copyTo(file("dist/spp-platform-$version/spp-cli"))
            file("processor/build/libs/spp-processor-$version.jar")
                .copyTo(file("dist/spp-processor-$version.jar"))
            file("interfaces/marker/build/spp-plugin-$version.zip")
                .copyTo(file("dist/spp-plugin-$version.zip"))
        }
    }

    register("downloadSkywalking") {
        doLast {
            val f = File(projectDir, "docker/e2e/apache-skywalking-apm-es7-$skywalkingVersion.tar.gz")
            if (!f.exists()) {
                println("Downloading Apache SkyWalking")
                URL("https://archive.apache.org/dist/skywalking/$skywalkingVersion/apache-skywalking-apm-es7-$skywalkingVersion.tar.gz")
                    .openStream().use { input ->
                        FileOutputStream(f).use { output ->
                            input.copyTo(output)
                        }
                    }
                println("Downloaded Apache SkyWalking")
            }
        }
    }

    register<Copy>("updateDockerFiles") {
        dependsOn(":platform:build", ":probe:control:build", ":processor:build")
        if (System.getProperty("build.profile") != "mini") {
            doFirst {
                if (!File("platform/build/graal/spp-platform").exists()) {
                    throw GradleException("Missing spp-platform")
                }
                if (!File("probe/control/build/libs/spp-probe-$version.jar").exists()) {
                    throw GradleException("Missing spp-probe-$version.jar")
                }
                if (!File("processor/build/libs/spp-processor-$version.jar").exists()) {
                    throw GradleException("Missing spp-processor-$version.jar")
                }
            }
            from(
                "platform/build/graal/spp-platform",
                "probe/control/build/libs/spp-probe-$version.jar",
                "processor/build/libs/spp-processor-$version.jar"
            )
            into(File(projectDir, "docker/e2e"))
        } else {
            doFirst {
                if (!File("platform/build/libs/spp-platform-$version.jar").exists()) {
                    throw GradleException("Missing spp-platform-$version.jar")
                }
                if (!File("probe/control/build/libs/spp-probe-$version-unprotected.jar").exists()) {
                    throw GradleException("Missing spp-probe-$version-unprotected.jar")
                }
                if (!File("processor/build/libs/spp-processor-$version-unprotected.jar").exists()) {
                    throw GradleException("Missing spp-processor-$version-unprotected.jar")
                }
            }
            from(
                "platform/build/libs/spp-platform-$version.jar",
                "probe/control/build/libs/spp-probe-$version-unprotected.jar",
                "processor/build/libs/spp-processor-$version-unprotected.jar"
            )
            into(File(projectDir, "docker/e2e"))
        }
    }
}

dockerCompose {
    dockerComposeWorkingDirectory = "./docker/e2e"
    removeVolumes = true

    if (System.getProperty("build.profile") != "mini") {
        useComposeFiles = listOf("docker-compose.yml")
    } else {
        useComposeFiles = listOf("docker-compose-mini.yml")
    }
    //captureContainersOutput = true
    captureContainersOutputToFile = File("./build/docker-compose.log")
}
