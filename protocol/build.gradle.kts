plugins {
    id("java")
}

// Import variables from gradle.properties file
val platformGroup: String by project
val platformName: String by project
val platformVersion: String by project

group = platformGroup
version = platformVersion

val vertxVersion = ext.get("vertxVersion")
val jacksonVersion = ext.get("jacksonVersion")

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
}
