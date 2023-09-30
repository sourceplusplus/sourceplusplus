plugins {
    kotlin("jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project
val skywalkingVersion: String by project
val skywalkingAgentVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/sourceplusplus")
            credentials {
                username = System.getenv("GH_PUBLISH_USERNAME")?.toString()
                password = System.getenv("GH_PUBLISH_TOKEN")?.toString()
            }
        }
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = platformGroup
                artifactId = "live-view-processor"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:common"))
    compileOnly("org.apache.skywalking:skywalking-meter-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-jvm-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-log-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-management-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-trace-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    testCompileOnly("org.apache.skywalking:apm-agent-core:$skywalkingAgentVersion") {
        isTransitive = false
    }

    testImplementation(project(":probes:jvm:boot"))
    testCompileOnly(project(":probes:jvm:common"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.20.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
    testImplementation("org.apache.skywalking:meter-analyzer:$skywalkingVersion") {
        //exclude network dependencies since agent shadows them
        exclude("org.apache.skywalking", "apm-network")
    }
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.apache.skywalking:apm-toolkit-trace:9.0.0")
}

tasks {
    test {
        val isIntegrationProfile = System.getProperty("test.profile") == "integration"
        val runningSpecificTests = gradle.startParameter.taskRequests.isNotEmpty()

        //exclude attaching probe to self unless requested
        if (isIntegrationProfile || runningSpecificTests) {
            dependsOn(":probes:jvm:boot:jar")

            val probeJar = "${project(":probes:jvm:boot").buildDir}/libs/spp-probe-$version.jar"
            jvmArgs = listOf("-javaagent:$probeJar=${projectDir}/src/test/resources/spp-test-probe.yml")
        }
    }

    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-view")
    }
}
