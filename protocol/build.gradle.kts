plugins {
    kotlin("jvm")
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
val compileKotlin: org.jetbrains.kotlin.gradle.tasks.KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: org.jetbrains.kotlin.gradle.tasks.KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
}
