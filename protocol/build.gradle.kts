plugins {
    id("java")
}

val platformGroup: String by project
val platformVersion: String by project
val jacksonVersion: String by project
val vertxVersion: String by project

group = platformGroup
version = platformVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
}
