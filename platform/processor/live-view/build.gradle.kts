plugins {
    kotlin("jvm")
}

val platformGroup: String by project
val projectVersion: String by project
val skywalkingVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

dependencies {
    compileOnly(project(":platform:common"))
    compileOnly(project(":platform:storage"))

    testImplementation(project(":probes:jvm:boot"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.19.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
    testImplementation("org.apache.skywalking:server-core:$skywalkingVersion")
    testImplementation("org.apache.skywalking:meter-analyzer:$skywalkingVersion")
    testImplementation("org.mockito:mockito-core:4.+")
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
        archiveBaseName.set("spp-live-view")
    }
}
