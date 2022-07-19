plugins {
    kotlin("jvm")
}

val processorGroup: String by project
val projectVersion: String by project

group = processorGroup
version = project.properties["processorVersion"] as String? ?: projectVersion

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
    maven(url = "https://pkg.sourceplus.plus/sourceplusplus/protocol")
}

dependencies {
    compileOnly(project(":platform:common"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-view")
    }
}
