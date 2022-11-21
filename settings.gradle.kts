pluginManagement {
    plugins {
        val kotlinVersion = "1.6.10"
        id("org.jetbrains.kotlin.jvm") version kotlinVersion apply false
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion apply false
        id("org.jetbrains.kotlin.js") version kotlinVersion apply false
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion apply false
        id("org.jetbrains.kotlin.kapt") version kotlinVersion apply false
        id("org.jetbrains.kotlin.plugin.noarg") version kotlinVersion apply false
        id("com.avast.gradle.docker-compose") version "0.16.11" apply false
        id("io.gitlab.arturbosch.detekt") version "1.22.0" apply false
        id("com.github.johnrengelman.shadow") version "7.1.2" apply false
        id("com.palantir.graal") version "0.12.0" apply false
        id("com.apollographql.apollo3") version "3.7.1" apply false
        id("org.mikeneck.graalvm-native-image") version "1.4.1" apply false
        id("com.diffplug.spotless") version "6.11.0" apply false
        id("com.github.node-gradle.node") version "3.5.0" apply false
    }
}

include("demos:groovy")
include("demos:java")
include("demos:kotlin")
include("demos:nodejs")
include("interfaces:cli")
include("interfaces:jetbrains:commander")
include("interfaces:jetbrains:commander:kotlin-compiler-wrapper")
include("interfaces:jetbrains:common")
include("interfaces:jetbrains:core")
include("interfaces:jetbrains:marker")
include("interfaces:jetbrains:marker:js-marker")
include("interfaces:jetbrains:marker:jvm-marker")
include("interfaces:jetbrains:marker:py-marker")
include("interfaces:jetbrains:marker:ult-marker")
include("interfaces:jetbrains:monitor")
include("interfaces:jetbrains:plugin")
include("interfaces:booster-ui")
include("platform:bridge")
include("platform:common")
include("platform:core")
include("platform:dashboard")
include("platform:storage")
include("platform:processor:live-instrument")
include("platform:processor:live-view")
include("probes:jvm:boot")
include("probes:jvm:common")
include("probes:jvm:services")
include("probes:python")
include("probes:nodejs")
include("protocol")
include("protocol:codegen")
include("tutorials:jvm")
include("tutorials:python")

include("example-web-app")
project(":example-web-app").projectDir = File("docker/e2e/example-web-app")
