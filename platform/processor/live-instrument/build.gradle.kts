plugins {
    kotlin("jvm")
}

val processorGroup: String by project
val projectVersion: String by project

group = processorGroup
version = project.properties["processorVersion"] as String? ?: projectVersion

dependencies {
    compileOnly(project(":platform:common"))

    //todo: properly add test dependency (also move common test code to common)
    testImplementation(project(":platform:core").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-instrument")
    }
}
