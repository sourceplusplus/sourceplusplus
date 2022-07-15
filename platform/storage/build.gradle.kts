plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

val platformGroup: String by project
val projectVersion: String by project
val vertxVersion: String by project
val jupiterVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = platformGroup
                artifactId = "platform-storage"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-redis-client:$vertxVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
}

tasks.getByName<Test>("test") {
    failFast = true
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        setExceptionFormat("full")

        outputs.upToDateWhen { false }
        showStandardStreams = true
    }
}
