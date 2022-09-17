plugins {
    id("com.diffplug.spotless")
    id("com.avast.gradle.docker-compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.noarg")
}

val platformGroup: String by project
val projectVersion: String by project
val jacksonVersion: String by project
val commonsLang3Version: String by project
val bouncycastleVersion: String by project
val jupiterVersion: String by project
val commonsIoVersion: String by project
val vertxVersion: String by project
val joorVersion: String by project
val slf4jVersion: String by project
val skywalkingVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

repositories {
    mavenCentral()
}

subprojects {
    if (name == "processor") return@subprojects
    repositories {
        mavenCentral()
        maven(url = "https://pkg.sourceplus.plus/sourceplusplus/protocol")
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.diffplug.spotless")
    apply<io.gitlab.arturbosch.detekt.DetektPlugin>()
    apply(plugin = "org.jetbrains.kotlin.plugin.noarg")

    val detektPlugins by configurations
    val implementation by configurations
    val compileOnly by configurations
    val testImplementation by configurations

    dependencies {
        implementation("plus.sourceplus:protocol:$projectVersion")
        implementation("org.apache.commons:commons-lang3:$commonsLang3Version")
        implementation("io.vertx:vertx-core:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
        compileOnly("org.slf4j:slf4j-api:$slf4jVersion")
        implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
        implementation("io.vertx:vertx-web:$vertxVersion")
        implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
        implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
        implementation("org.zeroturnaround:zt-zip:1.15")
        implementation("org.kohsuke:github-api:1.308")
        implementation("org.bouncycastle:bcprov-jdk15on:$bouncycastleVersion")
        implementation("org.bouncycastle:bcpkix-jdk15on:$bouncycastleVersion")
        implementation("org.apache.logging.log4j:log4j-core:2.19.0")

        implementation(files(File(findProject(":platform")!!.projectDir.parentFile, ".ext/vertx-redis-clustermanager-0.0.1-local.jar")))
        implementation("org.redisson:redisson:3.17.3")

        implementation("org.jooq:joor:$joorVersion")
        implementation("io.vertx:vertx-grpc-server:$vertxVersion") { exclude(group = "io.grpc") }
        implementation("io.vertx:vertx-grpc-client:$vertxVersion") { exclude(group = "io.grpc") }
        implementation("io.vertx:vertx-grpc-common:$vertxVersion") { exclude(group = "io.grpc") }
        implementation("com.google.protobuf:protobuf-java:3.21.6")
        implementation("io.vertx:vertx-service-discovery:$vertxVersion")
        implementation("io.vertx:vertx-service-proxy:$vertxVersion")
        implementation("io.vertx:vertx-health-check:$vertxVersion")
        implementation("io.vertx:vertx-web-graphql:$vertxVersion") {
            exclude(group = "com.graphql-java")
        }
        compileOnly("com.graphql-java:graphql-java:18.1")
        implementation("io.vertx:vertx-auth-jwt:$vertxVersion")
        implementation("io.vertx:vertx-redis-client:$vertxVersion")
        implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
        implementation("io.vertx:vertx-web-sstore-redis:$vertxVersion")
        implementation("org.bouncycastle:bcprov-jdk15on:$bouncycastleVersion")
        implementation("org.bouncycastle:bcpkix-jdk15on:$bouncycastleVersion")
        implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
        implementation("commons-io:commons-io:$commonsIoVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
        implementation("io.dropwizard.metrics:metrics-core:4.2.12")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
        implementation("org.apache.commons:commons-text:1.9")
        compileOnly("org.apache.skywalking:apm-network:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:library-server:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:library-module:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:telemetry-api:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:server-core:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:skywalking-sharing-server-plugin:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:library-client:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:skywalking-trace-receiver-plugin:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:agent-analyzer:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:event-analyzer:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:meter-analyzer:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:log-analyzer:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:storage-jdbc-hikaricp-plugin:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:storage-elasticsearch-plugin:$skywalkingVersion") { isTransitive = false }
        compileOnly("org.apache.skywalking:library-elasticsearch-client:$skywalkingVersion") { isTransitive = false }

        detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.21.0")

        testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
        testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
        testImplementation("io.vertx:vertx-junit5:$vertxVersion")
        testImplementation("io.vertx:vertx-web-client:$vertxVersion")
        testImplementation("org.apache.skywalking:agent-analyzer:$skywalkingVersion") { isTransitive = false }
        testImplementation("org.apache.skywalking:log-analyzer:$skywalkingVersion") { isTransitive = false }
    }

    spotless {
        kotlin {
            val startYear = 2022
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val copyrightYears = if (startYear == currentYear) {
                "$startYear"
            } else {
                "$startYear-$currentYear"
            }

            val licenseHeader = Regex("(    <one line[\\S\\s]+If not.+)")
                .find(File(project(":platform").projectDir.parentFile, "LICENSE").readText())!!
                .value.lines().joinToString("\n") {
                    if (it.trim().isEmpty()) {
                        " *"
                    } else {
                        " * " + it.trim()
                    }
                }
            val formattedLicenseHeader = buildString {
                append("/*\n")
                append(
                    licenseHeader.replace(
                        "<one line to give the program's name and a brief idea of what it does.>",
                        "Source++, the open-source live coding platform."
                    )
                        .replace("<year>", copyrightYears)
                        .replace(" <name of author>", "CodeBrig, Inc.")
                )
                append("\n */")
            }
            licenseHeader(formattedLicenseHeader)
        }
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt> {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(arrayOf(File(project.rootDir, "detekt.yml")))
    }

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

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
        kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=all")
    }

    configure<org.jetbrains.kotlin.noarg.gradle.NoArgExtension> {
        annotation("spp.platform.common.util.NoArg")
    }
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
    dependsOn("assemble")
    from(
        File(projectDir, "bridge/build/libs/spp-platform-bridge-${project.version}.jar"),
        File(projectDir, "core/build/libs/spp-platform-core-${project.version}.jar"),
        File(projectDir, "storage/build/libs/spp-platform-storage-${project.version}.jar"),
        File(projectDir, "dashboard/build/libs/spp-live-dashboard-${project.version}.jar"),
        File(projectDir, "processor/live-instrument/build/libs/spp-live-instrument-${project.version}.jar"),
        File(projectDir, "processor/live-view/build/libs/spp-live-view-${project.version}.jar"),
        File(projectDir, "../probes/jvm/control/build/libs/spp-probe-${project.version}.jar")
    ).into(File(projectDir, "../docker/e2e"))

    doFirst {
        File(projectDir, "../docker/e2e").listFiles()?.forEach {
            if (it.name.matches(Regex("(spp-(live|platform|probe)-.+\\.jar)"))) {
                it.delete()
            }
        }
    }
}

dockerCompose {
    dockerComposeWorkingDirectory.set(File("../docker/e2e"))
    removeVolumes.set(true)
    waitForTcpPorts.set(false)
}
tasks.getByName("composeUp").mustRunAfter(
    "updateDockerFiles",
    ":example-web-app:build"
)

tasks.register("assembleUp") {
    dependsOn(
        "updateDockerFiles",
        ":example-web-app:build", "composeUp"
    )
}

tasks.getByName("assemble") {
    dependsOn(
        ":platform:core:shadowJar",
        ":platform:bridge:jar",
        ":platform:storage:jar",
        ":platform:dashboard:jar",
        ":platform:processor:live-instrument:jar",
        ":platform:processor:live-view:jar",
        ":probes:jvm:control:jar"
    )
}
