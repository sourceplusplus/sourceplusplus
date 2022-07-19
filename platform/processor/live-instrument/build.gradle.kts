plugins {
    kotlin("jvm")
    id("maven-publish")
}

val processorGroup: String by project
val projectVersion: String by project

group = processorGroup
version = project.properties["processorVersion"] as String? ?: projectVersion

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
    maven(url = "https://pkg.sourceplus.plus/sourceplusplus/protocol")
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/processor-live-instrument")
            credentials {
                username = System.getenv("GH_PUBLISH_USERNAME")?.toString()
                password = System.getenv("GH_PUBLISH_TOKEN")?.toString()
            }
        }
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = processorGroup
                artifactId = "processor-live-instrument"
                version = project.version.toString()

                artifact("$buildDir/libs/spp-processor-live-instrument-${project.version}.jar")
            }
        }
    }
}

dependencies {
    compileOnly(project(":platform:common"))
//    compileOnly("plus.sourceplus:protocol:$projectVersion")
//    compileOnly("io.github.microutils:kotlin-logging-jvm:2.1.23")
//    compileOnly("org.jooq:joor:$joorVersion")
//    compileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
//    compileOnly("org.apache.skywalking:apm-network:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:library-server:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:library-module:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:telemetry-api:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:server-core:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:skywalking-sharing-server-plugin:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:library-client:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:skywalking-trace-receiver-plugin:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:agent-analyzer:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:event-analyzer:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:meter-analyzer:$skywalkingVersion") { isTransitive = false }
//    compileOnly("org.apache.skywalking:log-analyzer:$skywalkingVersion") { isTransitive = false }
//    compileOnly("io.vertx:vertx-service-discovery:$vertxVersion")
//    compileOnly("io.vertx:vertx-service-proxy:$vertxVersion")
//    compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
//    compileOnly("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
//    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.3")
//    compileOnly("io.vertx:vertx-core:$vertxVersion")
//    compileOnly("io.vertx:vertx-lang-kotlin:$vertxVersion")
//    compileOnly("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
//    compileOnly("io.vertx:vertx-auth-common:$vertxVersion")
//    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
//    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
//    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-guava:$jacksonVersion")
//    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
//    compileOnly("org.slf4j:slf4j-api:$slf4jVersion")
//    compileOnly("com.google.guava:guava:31.1-jre")
//    compileOnly("io.grpc:grpc-stub:$grpcVersion") {
//        exclude(mapOf("group" to "com.google.guava", "module" to "guava"))
//    }
//    compileOnly("io.grpc:grpc-netty:$grpcVersion") {
//        exclude(mapOf("group" to "com.google.guava", "module" to "guava"))
//    }
//    compileOnly("io.grpc:grpc-protobuf:$grpcVersion") {
//        exclude(mapOf("group" to "com.google.guava", "module" to "guava"))
//    }
//
//    testImplementation("io.vertx:vertx-core:$vertxVersion")
//    testImplementation("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
//    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
//    testImplementation("io.vertx:vertx-web-client:$vertxVersion")
//    testImplementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
//    testImplementation("io.vertx:vertx-tcp-eventbus-bridge:$vertxVersion")
//    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.3")
//    testImplementation("io.vertx:vertx-service-proxy:$vertxVersion")
//    testImplementation("org.slf4j:slf4j-api:$slf4jVersion")
//    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
//    testImplementation("com.google.guava:guava:31.0.1-jre")
//    testImplementation("org.apache.skywalking:agent-analyzer:$skywalkingVersion")
//    testImplementation("org.apache.skywalking:log-analyzer:$skywalkingVersion")
//    testImplementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
//    testImplementation("plus.sourceplus:protocol:$projectVersion")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    jar {
        archiveBaseName.set("spp-live-instrument")
    }
}
