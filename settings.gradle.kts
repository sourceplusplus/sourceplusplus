pluginManagement {
    plugins {
        val kotlinVersion = "1.8.10"
        id("org.jetbrains.kotlin.jvm") version kotlinVersion apply false
        id("org.jetbrains.kotlin.kapt") version kotlinVersion apply false
        id("org.jetbrains.kotlin.plugin.noarg") version kotlinVersion apply false
        id("com.avast.gradle.docker-compose") version "0.16.11" apply false
        id("io.gitlab.arturbosch.detekt") version "1.22.0" apply false
        id("com.github.johnrengelman.shadow") version "8.1.0" apply false
        id("com.apollographql.apollo3") version "3.7.4" apply false
        id("org.mikeneck.graalvm-native-image") version "1.4.1" apply false
        id("com.diffplug.spotless") version "6.16.0" apply false
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
include("interfaces:jetbrains:insight")
include("interfaces:jetbrains:marker")
include("interfaces:jetbrains:marker:js-marker")
include("interfaces:jetbrains:marker:jvm-marker")
include("interfaces:jetbrains:marker:py-marker")
include("interfaces:jetbrains:marker:ult-marker")
include("interfaces:jetbrains:monitor")
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
include("protocol:codegen")
include("tutorials:jvm")
include("tutorials:python")

plugins {
    id("org.danilopianini.gradle-pre-commit-git-hooks") version "1.1.3"
}

gitHooks {
    commitMsg {
        conventionalCommits {
            types("build") //Changes that affect the build system or external dependencies
            types("ci") //Changes to CI configuration files and scripts
            types("docs") //Documentation only changes
            types("feat") //A new feature
            types("fix") //A bug fix
            types("refactor") //Rewrite/restructure code without any changes in functionality
            types("perf") //Refactors that improve performance
            types("style") //Formatting, missing semi colons, etc; no code change
            types("test") //Adding missing tests or correcting existing tests
            types("ide") //Changes that affect IDE setup
            types("chore") //Miscellaneous changes (automated dependency updates, etc)
        }
    }
    createHooks()
}
