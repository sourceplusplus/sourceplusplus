import java.util.*

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.github.johnrengelman.shadow")
    id("com.palantir.graal")
    id("org.jetbrains.kotlin.jvm")
}

val platformGroup: String by project
val platformVersion: String by project
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
val protocolVersion: String by project

group = platformGroup
version = platformVersion

dependencies {
    implementation(project(":platform:services"))
    implementation(project(":platform:common"))
    implementation("org.kohsuke:github-api:1.301")

    shadow(project(":processors:instrument")) //todo: figure out why extra configurations.add() and this are needed
    shadow(project(":processors:log-summary")) //todo: figure out why extra configurations.add() and this are needed
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
    graalVersion(project.properties["graalVersion"] as String)
    javaVersion("11")
    mainClass("spp.platform.SourcePlatform")
    outputName("spp-platform")
    option("-H:+PrintClassInitialization")
    option("-H:+ReportExceptionStackTraces")
//    option("-H:+TraceClassInitialization")
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
