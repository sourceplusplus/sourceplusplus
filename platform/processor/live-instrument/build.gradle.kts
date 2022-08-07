plugins {
    kotlin("jvm")
}

val platformGroup: String by project
val projectVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

dependencies {
    compileOnly(project(":platform:common"))

    testImplementation(project(":probes:jvm:control"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.18.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
}

tasks {
    test {
        jvmArgs = listOf("-javaagent:${rootProject.projectDir}/docker/e2e/spp-probe-$version.jar=${project.projectDir}/src/test/resources/spp-test-probe.yml")
    }

    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-instrument")
    }
}
