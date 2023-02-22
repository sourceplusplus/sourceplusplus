plugins {
    kotlin("jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project

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
                artifactId = "live-instrument-processor"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    compileOnly(project(":platform:common"))
    compileOnly(project(":platform:storage"))

    testImplementation(project(":probes:jvm:boot"))
    testImplementation("org.apache.logging.log4j:log4j-core:2.20.0")
    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
}

tasks {
    test {
        dependsOn(":probes:jvm:boot:jar")
        val probeJar = "${project(":probes:jvm:boot").buildDir}/libs/spp-probe-$version.jar"

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
