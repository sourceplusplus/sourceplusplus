plugins {
    id("com.avast.gradle.docker-compose")
    id("io.gitlab.arturbosch.detekt") apply false
    id("com.github.johnrengelman.shadow") apply false
    id("com.palantir.graal") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

val platformGroup: String by project
val platformVersion: String by project
val processorDependenciesVersion: String by project
val instrumentProcessorVersion: String by project
val logSummaryProcessorVersion: String by project

group = platformGroup
version = platformVersion

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    if (name == "core") {
        apply(plugin = "com.github.johnrengelman.shadow")
        apply(plugin = "com.palantir.graal")
    }

    val graalVersion: String by project
    val jacksonVersion: String by project
    val commonsLang3Version: String by project
    val cliktVersion: String by project
    val bouncycastleVersion: String by project
    val jupiterVersion: String by project
    val apolloVersion: String by project
    val commonsIoVersion: String by project
    val logbackVersion: String by project
    val auth0JwtVersion: String by project
    val vertxVersion = "4.0.3" //todo: consolidate with main project

    repositories {
        mavenCentral()
        jcenter()
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations

        implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
        implementation(project(":protocol"))
        implementation(project(":processors:instrument"))
        implementation(project(":processors:log-summary"))
        if (name == "services") {
            val compileOnly by configurations
            compileOnly(project(":platform:common"))
        }

        implementation("io.github.microutils:kotlin-logging-jvm:2.1.0")
        implementation("org.apache.commons:commons-lang3:$commonsLang3Version")
        implementation("com.github.ajalt.clikt:clikt:$cliktVersion")
        implementation("ch.qos.logback:logback-classic:$logbackVersion")
        implementation("io.vertx:vertx-service-discovery:$vertxVersion")
        implementation(files("../.ext/vertx-service-proxy-4.0.2.jar"))
        implementation("io.vertx:vertx-health-check:$vertxVersion")
        implementation("io.vertx:vertx-web-graphql:$vertxVersion")
        implementation("io.vertx:vertx-auth-jwt:$vertxVersion")
        implementation("io.vertx:vertx-redis-client:$vertxVersion")
        implementation("io.vertx:vertx-web-graphql:${vertxVersion}")
        implementation(files("../.ext/vertx-tcp-eventbus-bridge-4.0.3-SNAPSHOT.jar"))
        implementation("com.auth0:java-jwt:$auth0JwtVersion")
        implementation("com.auth0:jwks-rsa:0.20.0")
        implementation("org.bouncycastle:bcprov-jdk15on:$bouncycastleVersion")
        implementation("org.bouncycastle:bcpkix-jdk15on:$bouncycastleVersion")
        implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
        implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
        implementation("commons-io:commons-io:$commonsIoVersion")
        implementation("com.apollographql.apollo:apollo-runtime:$apolloVersion")
        implementation("org.zeroturnaround:zt-zip:1.14")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
        implementation("io.vertx:vertx-core:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
        implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
        implementation("io.vertx:vertx-web:$vertxVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
        implementation("io.dropwizard.metrics:metrics-core:4.2.4")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.1")

        testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
        testImplementation("io.vertx:vertx-junit5:$vertxVersion")
        testImplementation("io.vertx:vertx-web-client:$vertxVersion")
    }
}

tasks.register("clean") {
    doFirst {
        File(projectDir, "../docker/e2e").listFiles()?.forEach {
            if (it.name.startsWith("spp-platform-") || it.name.startsWith("spp-processor-")) {
                it.delete()
            }
        }
    }
}

tasks.register<Copy>("updateDockerFiles") {
    dependsOn(
        ":platform:core:jar", ":processors:dependencies:jar",
        ":processors:instrument:jar", ":processors:log-summary:jar"
    )
    if (System.getProperty("build.profile") == "debian") {
        doFirst {
            if (!File(projectDir, "core/build/graal/spp-platform").exists()) {
                throw GradleException("Missing spp-platform")
            }
        }
        from(File(projectDir, "core/build/graal/spp-platform"))
            .into(File(projectDir, "../docker/e2e"))
    } else {
        doFirst {
            if (!File(projectDir, "core/build/libs/spp-platform-$version.jar").exists()) {
                throw GradleException("Missing spp-platform-$version.jar")
            }
        }
        from(File(projectDir, "core/build/libs/spp-platform-$version.jar"))
            .into(File(projectDir, "../docker/e2e"))
    }
    doFirst {
        if (!File(projectDir, "../processors/dependencies/build/libs/spp-processor-dependencies-$processorDependenciesVersion.jar").exists()) {
            throw GradleException("Missing spp-processor-dependencies-$processorDependenciesVersion.jar")
        }
        if (!File(projectDir, "../processors/instrument/build/libs/spp-processor-instrument-$instrumentProcessorVersion.jar").exists()) {
            throw GradleException("Missing spp-processor-instrument-$instrumentProcessorVersion.jar")
        }
        if (!File(projectDir, "../processors/log-summary/build/libs/spp-processor-log-summary-$logSummaryProcessorVersion.jar").exists()) {
            throw GradleException("Missing spp-processor-log-summary-$logSummaryProcessorVersion.jar")
        }

        File(projectDir, "../docker/e2e").listFiles()?.forEach {
            if (it.name.startsWith("spp-platform-") || it.name.startsWith("spp-processor-")) {
                it.delete()
            }
        }
    }

    from(File(projectDir, "../processors/dependencies/build/libs/spp-processor-dependencies-$processorDependenciesVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))
    from(File(projectDir, "../processors/instrument/build/libs/spp-processor-instrument-$instrumentProcessorVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))
    from(File(projectDir, "../processors/log-summary/build/libs/spp-processor-log-summary-$logSummaryProcessorVersion.jar"))
        .into(File(projectDir, "../docker/e2e"))
}

dockerCompose {
    dockerComposeWorkingDirectory.set(File("../docker/e2e"))
    removeVolumes.set(true)
    waitForTcpPorts.set(false)

    if (System.getProperty("build.profile") == "debian") {
        useComposeFiles.set(listOf("docker-compose-debian.yml"))
    } else {
        useComposeFiles.set(listOf("docker-compose-jvm.yml"))
    }
}
tasks.getByName("composeUp").mustRunAfter("updateDockerFiles")
