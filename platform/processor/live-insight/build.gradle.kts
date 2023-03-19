plugins {
    kotlin("jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project
val vertxVersion: String by project

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
                artifactId = "live-insight-processor"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

configurations.all {
    exclude(group = "org.jetbrains.pty4j", module = "pty4j")
    exclude(group = "org.jetbrains.intellij", module = "blockmap")
    exclude(group = "ai.grazie.spell")
    exclude(group = "ai.grazie.nlp")
    exclude(group = "ai.grazie.utils")
    exclude(group = "ai.grazie.model")
    exclude(group = "io.ktor", module = "ktor-network-jvm")
    exclude(group = "com.jetbrains.infra")
    exclude(group = "com.github.ben-manes.caffeine", module = "caffeine")
}

repositories {
    mavenCentral()
    maven(url = "https://pkg.sourceplus.plus/sourceplusplus/interface-jetbrains")
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    maven(url = "https://www.jetbrains.com/intellij-repository/snapshots")
    maven(url = "https://cache-redirector.jetbrains.com/intellij-dependencies/")
}

dependencies {
    compileOnly(project(":platform:common"))
    compileOnly(project(":platform:storage"))
    compileOnly(project(":platform:processor:live-view"))

    compileOnly("plus.sourceplus.platform:platform-common:0.7.8-SNAPSHOT")
    compileOnly("plus.sourceplus.platform:platform-storage:0.7.8-SNAPSHOT")
    compileOnly("plus.sourceplus.platform:live-view-processor:0.7.8-SNAPSHOT")
    compileOnly("plus.sourceplus.interface:jetbrains-core:0.7.8-SNAPSHOT")
    compileOnly("plus.sourceplus.interface:jetbrains-common:0.7.8-SNAPSHOT")
    compileOnly("plus.sourceplus.interface:jetbrains-marker:0.7.8-SNAPSHOT")
    compileOnly("plus.sourceplus.interface:jetbrains-marker-jvm:0.7.8-SNAPSHOT")
//    implementation("plus.sourceplus.interface:jetbrains-marker-ult:0.7.8-SNAPSHOT")

    val intellijVersion = "231.8109.2-EAP-SNAPSHOT"
    implementation("com.jetbrains.intellij.platform:core:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:core-impl:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:lang:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:lang-impl:$intellijVersion")
    implementation("com.jetbrains.intellij.java:java:$intellijVersion")
    implementation("com.jetbrains.intellij.java:java-impl:$intellijVersion")
    implementation("com.jetbrains.intellij.java:java-psi:$intellijVersion")

    implementation("org.zeroturnaround:zt-exec:1.12")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
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
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    jar {
        archiveBaseName.set("spp-live-insight")
    }
}
