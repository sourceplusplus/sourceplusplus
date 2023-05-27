pluginManagement {
    plugins {
        val kotlinVersion = "1.8.21"
        id("org.jetbrains.kotlin.jvm") version kotlinVersion apply false
        id("org.jetbrains.kotlin.kapt") version kotlinVersion apply false
        id("org.jetbrains.kotlin.plugin.noarg") version kotlinVersion apply false
        id("com.avast.gradle.docker-compose") version "0.16.12" apply false
        id("io.gitlab.arturbosch.detekt") version "1.23.0" apply false
        id("com.github.johnrengelman.shadow") version "8.1.1" apply false
        id("com.apollographql.apollo3") version "3.8.2" apply false
        id("org.mikeneck.graalvm-native-image") version "1.4.1" apply false
        id("com.diffplug.spotless") version "6.19.0" apply false
    }
}

include("demos:groovy")
include("demos:java")
include("demos:kotlin")
include("demos:nodejs")
include("interfaces:cli")
include("interfaces:jetbrains:commander")
include("interfaces:jetbrains:commander:kotlin-compiler-wrapper")
include("interfaces:jetbrains:core")
include("interfaces:jetbrains:insight")
include("interfaces:jetbrains:marker")
include("interfaces:jetbrains:marker:js-marker")
include("interfaces:jetbrains:marker:jvm-marker")
include("interfaces:jetbrains:marker:py-marker")
include("interfaces:jetbrains:marker:ult-marker")
include("interfaces:jetbrains:plugin")
include("platform:bridge")
include("platform:common")
include("platform:core")
include("platform:storage")
include("platform:processor:live-instrument")
include("platform:processor:live-view")
include("probes:jvm:boot")
include("probes:jvm:common")
include("probes:jvm:services")
include("probes:python")
include("probes:nodejs")
include("protocol")
include("tutorials:jvm")
include("tutorials:python")
