plugins {
    id("com.github.johnrengelman.shadow")
    kotlin("jvm")
    kotlin("kapt")
}

val processorGroup: String by project
val instrumentProcessorVersion: String by project
val skywalkingVersion: String by project
val vertxVersion: String by project
val gsonVersion: String by project
val grpcVersion: String by project
val sourceMarkerVersion: String by project
val protocolVersion: String by project
val jacksonVersion: String by project
val kotlinVersion: String by project

group = processorGroup
version = instrumentProcessorVersion

repositories {
    mavenCentral()
    jcenter()
    maven(url = "https://kotlin.bintray.com/kotlinx/")
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    compileOnly("org.apache.skywalking:apm-network:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:library-server:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:library-module:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:log-analyzer:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:telemetry-api:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:server-core:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:skywalking-sharing-server-plugin:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:storage-elasticsearch-plugin:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:library-elasticsearch-client:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:library-client:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:skywalking-trace-receiver-plugin:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:agent-analyzer:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:event-analyzer:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.apache.skywalking:meter-analyzer:$skywalkingVersion") { isTransitive = false }
    compileOnly("org.elasticsearch:elasticsearch:7.15.1")
    implementation("io.vertx:vertx-service-discovery:$vertxVersion")
    implementation(files(".ext/vertx-service-proxy-4.0.2.jar"))
    implementation("io.vertx:vertx-codegen:$vertxVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.1")
    kapt("io.vertx:vertx-codegen:$vertxVersion:processor")
    annotationProcessor("io.vertx:vertx-service-proxy:$vertxVersion")
    implementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("com.google.guava:guava:31.0.1-jre")
    implementation("io.grpc:grpc-stub:$grpcVersion") {
        exclude(mapOf("group" to "com.google.guava", "module" to "guava"))
    }
    compileOnly("io.grpc:grpc-netty:$grpcVersion") {
        exclude(mapOf("group" to "com.google.guava", "module" to "guava"))
    }
    compileOnly("io.grpc:grpc-protobuf:$grpcVersion") {
        exclude(mapOf("group" to "com.google.guava", "module" to "guava"))
    }
}

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("spp-processor-instrument")
    archiveClassifier.set("")

//    exclude("google/**")
//    exclude("kotlin/**/*.kotlin_metadata")
//    exclude("kotlin/**/*.kotlin_builtins")
//    exclude("META-INF/*.kotlin_module")
//    exclude("DebugProbesKt.bin")
//    exclude("lang-type-mapping.properties")
//    exclude("module-info.class")
//    exclude("META-INF/maven/**")
//    exclude("META-INF/native-image/**")
//    exclude("META-INF/proguard/**")
//    exclude("META-INF/services/com.fasterxml.*")
//    exclude("META-INF/services/io.vertx.*")
//    exclude("META-INF/services/kotlin.reflect.*")
//    exclude("META-INF/services/reactor.blockhound.*")
//    exclude("META-INF/versions/**")
//    exclude("META-INF/vertx/vertx-service-proxy/**")
//    exclude("META-INF/vertx/web/**")
//    exclude("META-INF/vertx/vertx-version.txt")
//    exclude("META-INF/INDEX.LIST")
//    exclude("META-INF/LICENSE")
//    exclude("META-INF/NOTICE")
//    exclude("META-INF/io.netty.versions.properties")

    relocate("com.fasterxml", "spp.processor.common.com.fasterxml")
    relocate("com.google.common", "spp.processor.common.com.google.common")
    relocate("com.google.errorprone", "spp.processor.common.com.google.errorprone")
    relocate("com.google.gson", "spp.processor.common.com.google.gson")
    relocate("com.google.j2objc", "spp.processor.common.com.google.j2objc")
    relocate("com.google.thirdparty", "spp.processor.common.com.google.thirdparty")
    relocate("io.netty", "spp.processor.common.io.netty")
    relocate("io.vertx", "spp.processor.common.io.vertx")
//    relocate("kotlin", "spp.processor.common.kotlin")
//    relocate("kotlinx", "spp.processor.common.kotlinx")
    relocate("org.slf4j", "spp.processor.common.org.slf4j")
//    minimize()
}
tasks.getByName("build").dependsOn("shadowJar")
