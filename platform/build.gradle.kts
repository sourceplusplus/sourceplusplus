import java.util.*

plugins {
    id("com.github.johnrengelman.shadow") version "7.1.0"
    id("com.palantir.graal") version "0.9.0"
    id("com.apollographql.apollo").version("2.5.9")
}

val platformGroup: String by project
val platformVersion: String by project
val graalVersion: String by project
val jacksonVersion: String by project
val sourceMarkerVersion: String by project
val commonsLang3Version: String by project
val cliktVersion: String by project
val bouncycastleVersion: String by project
val jupiterVersion: String by project
val apolloVersion: String by project
val commonsIoVersion: String by project
val logbackVersion: String by project
val auth0JwtVersion: String by project

group = platformGroup
version = platformVersion

val vertxVersion = "4.1.4" //todo: consolidate with gradle.properties 4.0.2

dependencies {
    implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
    implementation("com.github.sourceplusplus:sourcemarker:v$sourceMarkerVersion") {
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "portal-js"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "portal-metadata"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "protocol-js"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "protocol-metadata"))
        exclude(mapOf("group" to "SourceMarker.monitor", "module" to "skywalking"))
    }
    implementation(project(":protocol"))
    implementation(project(":processor"))
    shadow(project(":processor")) //todo: figure out why extra configurations.add() and this are needed

    implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")
    implementation("org.apache.commons:commons-lang3:$commonsLang3Version")
    implementation("com.github.ajalt.clikt:clikt:$cliktVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.vertx:vertx-service-discovery:$vertxVersion")
    implementation("io.vertx:vertx-service-proxy:$vertxVersion")
    implementation("io.vertx:vertx-codegen:$vertxVersion")
    kapt("io.vertx:vertx-codegen:$vertxVersion:processor")
//    annotationProcessor("io.vertx:vertx-service-proxy:$vertxVersion")
    implementation(files(".ext/vertx-service-proxy-4.0.2.jar"))
    implementation("io.vertx:vertx-health-check:$vertxVersion")
    implementation("io.vertx:vertx-web-graphql:$vertxVersion")
    implementation("io.vertx:vertx-auth-jwt:$vertxVersion")
    implementation("io.vertx:vertx-redis-client:$vertxVersion")
    implementation("io.vertx:vertx-web-graphql:${vertxVersion}")
    implementation(files(".ext/vertx-tcp-eventbus-bridge-4.0.3-SNAPSHOT.jar"))
    implementation("com.auth0:java-jwt:$auth0JwtVersion")
    implementation("com.auth0:jwks-rsa:0.20.0")
    implementation("org.bouncycastle:bcprov-jdk15on:$bouncycastleVersion")
    implementation("org.bouncycastle:bcpkix-jdk15on:$bouncycastleVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("commons-io:commons-io:$commonsIoVersion")
    implementation("com.apollographql.apollo:apollo-runtime:$apolloVersion")
    implementation("org.zeroturnaround:zt-zip:1.14")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
}

//todo: shouldn't need to put in src (github actions needs for some reason)
tasks.create("createProperties") {
    if (System.getProperty("build.profile") == "debian") {
        val buildBuildFile = File(projectDir, "src/main/resources/build.properties")
        if (buildBuildFile.exists()) {
            buildBuildFile.delete()
        } else {
            buildBuildFile.parentFile.mkdirs()
        }

        buildBuildFile.writer().use {
            val p = Properties()
            p["build_version"] = project.version.toString()
            p.store(it, null)
        }
    }
}
tasks["processResources"].dependsOn("createProperties")

graal {
    //graalVersion(graalVersion.toString())
    mainClass("spp.platform.SourcePlatform")
    outputName("spp-platform")
    option("-H:+PrintClassInitialization")
    option("-H:+ReportExceptionStackTraces")
    option("-H:+TraceClassInitialization")
    option("-H:IncludeResourceBundles=build")
}

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
