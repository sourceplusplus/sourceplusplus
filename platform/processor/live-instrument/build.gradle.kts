plugins {
    kotlin("jvm")
}

val platformGroup: String by project
val projectVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

dependencies {
    compileOnly(project(":platform:common"))

    testImplementation(project(":probes:jvm:boot"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.19.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
}

tasks {
    test {
        val probeJar = "${rootProject.projectDir}/docker/e2e/spp-probe-$version.jar"

        //todo: should have way to distinguish tests that just need platform and tests that attach to self
        val isIntegrationProfile = System.getProperty("test.profile") == "integration"
        val runningSpecificTests = gradle.startParameter.taskNames.contains("--tests")

        //exclude attaching probe to self unless requested
        if (isIntegrationProfile || runningSpecificTests) {
            jvmArgs = listOf("-javaagent:$probeJar=${projectDir}/src/test/resources/spp-test-probe.yml")
        }
    }

    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-instrument")
    }
}
