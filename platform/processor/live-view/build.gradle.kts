plugins {
    kotlin("jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project
val skywalkingVersion: String by project

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
    compileOnly(project(":platform:common"))
    compileOnly(project(":platform:storage"))
    compileOnly("org.apache.skywalking:skywalking-meter-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-jvm-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-log-recevier-plugin:$skywalkingVersion") {
        isTransitive = false
    }
    compileOnly("org.apache.skywalking:skywalking-management-receiver-plugin:$skywalkingVersion") {
        isTransitive = false
    }

    testImplementation(project(":probes:jvm:boot"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.20.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
    testImplementation("org.apache.skywalking:meter-analyzer:$skywalkingVersion") {
        //exclude network dependencies since agent shadows them
        exclude("org.apache.skywalking", "apm-network")
    }
    testImplementation("org.mockito:mockito-core:5.4.0")
    testImplementation("org.apache.skywalking:apm-toolkit-trace:8.16.0")
}

tasks {
    test {
        val isIntegrationProfile = System.getProperty("test.profile") == "integration"
        val testsIndex = gradle.startParameter.taskNames.indexOf("--tests")
        var runningIntegrationTests = false
        if (testsIndex != -1) {
            val testName = gradle.startParameter.taskNames[testsIndex + 1]
            if (testName.contains("integration.") || testName.endsWith("IT")) {
                runningIntegrationTests = true
            }
        }

        //exclude attaching probe to self unless requested
        if (isIntegrationProfile || runningIntegrationTests) {
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
