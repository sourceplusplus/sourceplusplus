import java.util.*

plugins {
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("com.palantir.graal") version "0.7.2"
    id("com.apollographql.apollo").version("2.5.6")
}

// Import variables from gradle.properties file
val platformGroup: String by project
val platformName: String by project
val platformVersion: String by project

group = platformGroup
version = platformVersion

val vertxVersion = "4.0.3"
val graalVersion = ext.get("graalVersion")
val jacksonVersion = ext.get("jacksonVersion")

dependencies {
    implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
    implementation("com.github.sourceplusplus:sourcemarker:19ab6d805e") {
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "portal-js"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "portal-metadata"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "protocol-js"))
        exclude(mapOf("group" to "com.github.sourceplusplus.sourcemarker", "module" to "protocol-metadata"))
        exclude(mapOf("group" to "SourceMarker.monitor", "module" to "skywalking"))
    }
    implementation(project(":protocol"))
    implementation(project(":processor"))
    shadow(project(":processor")) //todo: figure out why extra configurations.add() and this are needed

    implementation("io.github.microutils:kotlin-logging-jvm:2.0.8")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("com.github.ajalt.clikt:clikt:3.2.0")
    implementation("ch.qos.logback:logback-classic:1.2.3")
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
    implementation("com.auth0:java-jwt:3.14.0")
    implementation("com.auth0:jwks-rsa:0.17.0")
    implementation("com.flagsmith:flagsmith-java-client:2.3")
    implementation("org.bouncycastle:bcprov-jdk15on:1.68")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.68")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.apollographql.apollo:apollo-runtime:2.5.6")
    implementation("org.zeroturnaround:zt-zip:1.14")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.7.2")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
}

//todo: shouldn't need to put in src (github actions needs for some reason)
tasks.create("createProperties") {
    if (System.getProperty("build.profile") == "full") {
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
