import java.util.*

plugins {
    id("com.diffplug.spotless")
    id("com.avast.gradle.docker-compose")
    id("io.gitlab.arturbosch.detekt")
    id("com.github.johnrengelman.shadow")
    id("org.jetbrains.kotlin.jvm")
}

val platformGroup: String by project
val projectVersion: String by project
val jacksonVersion: String by project
val commonsLang3Version: String by project
val cliktVersion: String by project
val bouncycastleVersion: String by project
val jupiterVersion: String by project
val commonsIoVersion: String by project
val logbackVersion: String by project
val auth0JwtVersion: String by project
val vertxVersion: String by project
val joorVersion: String by project

group = platformGroup
version = projectVersion

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":processors:dependencies"))

    implementation("org.kohsuke:github-api:1.306")
    implementation("org.jooq:joor:$joorVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
    implementation("org.apache.commons:commons-lang3:$commonsLang3Version")
    implementation("com.github.ajalt.clikt:clikt:$cliktVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.vertx:vertx-service-discovery:$vertxVersion")
    implementation("io.vertx:vertx-service-proxy:$vertxVersion")
    implementation("io.vertx:vertx-health-check:$vertxVersion")
    implementation("io.vertx:vertx-web-graphql:$vertxVersion")
    implementation("io.vertx:vertx-auth-jwt:$vertxVersion")
    implementation("io.vertx:vertx-redis-client:$vertxVersion")
    implementation("io.vertx:vertx-web-graphql:${vertxVersion}")
    implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
    implementation("com.auth0:java-jwt:$auth0JwtVersion")
    implementation("com.auth0:jwks-rsa:0.21.1")
    implementation("org.bouncycastle:bcprov-jdk15on:$bouncycastleVersion")
    implementation("org.bouncycastle:bcpkix-jdk15on:$bouncycastleVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("commons-io:commons-io:$commonsIoVersion")
    implementation("org.zeroturnaround:zt-zip:1.15")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.2")
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("io.dropwizard.metrics:metrics-core:4.2.9")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.3")
    implementation("org.apache.commons:commons-text:1.9")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
}

tasks.register("cleanDockerSetup") {
    doFirst {
        File(projectDir, "../docker/e2e").listFiles()?.forEach {
            if (it.name.endsWith(".jar")) it.delete()
        }
    }
}
tasks.getByName("clean").dependsOn("cleanDockerSetup")

tasks.register<Copy>("updateDockerFiles") {
    dependsOn(
        ":platform:jar", ":probes:jvm:control:jar",
        ":processors:dependencies:jar",
        ":processors:live-instrument:jar",
        ":processors:live-view:jar"
    )

    doFirst {
        if (!File(projectDir, "build/libs/spp-platform-$projectVersion.jar").exists()) {
            throw GradleException("Missing spp-platform-$projectVersion.jar")
        }
    }
    from(File(projectDir, "build/libs/spp-platform-$projectVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))

    doFirst {
        if (!File(projectDir, "../probes/jvm/control/build/libs/spp-probe-$projectVersion.jar").exists()) {
            throw GradleException("Missing spp-probe-$projectVersion.jar")
        }
    }
    from(File(projectDir, "../probes/jvm/control/build/libs/spp-probe-$projectVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))

    doFirst {
        if (!File(projectDir, "../processors/dependencies/build/libs/spp-processor-dependencies-$projectVersion.jar").exists()) {
            throw GradleException("Missing spp-processor-dependencies-$projectVersion.jar")
        }
        if (!File(projectDir, "../processors/live-instrument/build/libs/spp-processor-live-instrument-$projectVersion.jar").exists()) {
            throw GradleException("Missing spp-processor-live-instrument-$projectVersion.jar")
        }
        if (!File(projectDir, "../processors/live-view/build/libs/spp-processor-live-view-$projectVersion.jar").exists()) {
            throw GradleException("Missing spp-processor-live-view-$projectVersion.jar")
        }

        File(projectDir, "../docker/e2e").listFiles()?.forEach {
            if (it.name.startsWith("spp-platform-") || it.name.startsWith("spp-processor-")) {
                it.delete()
            }
        }
    }

    from(File(projectDir, "../processors/dependencies/build/libs/spp-processor-dependencies-$projectVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))
    from(File(projectDir, "../processors/live-instrument/build/libs/spp-processor-live-instrument-$projectVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))
    from(File(projectDir, "../processors/live-view/build/libs/spp-processor-live-view-$projectVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))
}

dockerCompose {
    dockerComposeWorkingDirectory.set(File("../docker/e2e"))
    removeVolumes.set(true)
    waitForTcpPorts.set(false)
}
tasks.getByName("composeUp").mustRunAfter("updateDockerFiles", ":example-web-app:build")

tasks.register("assembleUp") {
    dependsOn("updateDockerFiles", ":example-web-app:build", "composeUp")
}

//todo: shouldn't need to put in src (github actions needs for some reason)
tasks.create("createProperties") {
    if (System.getProperty("build.profile") == "release") {
        val buildBuildFile = File(projectDir, "src/main/resources/build.properties")
        if (buildBuildFile.exists()) {
            buildBuildFile.delete()
        } else {
            buildBuildFile.parentFile.mkdirs()
        }

        buildBuildFile.writer().use {
            val p = Properties()
            p["build_id"] = UUID.randomUUID().toString()
            p["build_date"] = Date().toInstant().toString()
            p["build_version"] = project.version.toString()
            p.store(it, null)
        }
    }
}
tasks["processResources"].dependsOn("createProperties")

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("spp-platform")
    archiveClassifier.set("")
    manifest {
        attributes(
            mapOf(
                "Main-Class" to "spp.platform.SourcePlatform"
            )
        )
    }
    configurations.add(project.configurations.compileClasspath.get())
    configurations.add(project.configurations.runtimeClasspath.get())
    configurations.add(project.configurations.shadow.get())
}
tasks.getByName("jar").dependsOn("shadowJar")

tasks.getByName<Test>("test") {
    failFast = true
    useJUnitPlatform()
    if (System.getProperty("test.profile") != "integration") {
        exclude("integration/**")
    }

    testLogging {
        events("passed", "skipped", "failed")
        setExceptionFormat("full")

        outputs.upToDateWhen { false }
        showStandardStreams = true
    }
}

spotless {
    kotlin {
        licenseHeaderFile(file("LICENSE-HEADER.txt"))
    }
}
