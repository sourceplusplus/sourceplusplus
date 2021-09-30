buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.1.1")
    }
}

plugins {
    id("com.github.johnrengelman.shadow") version "7.0.0"
    id("java")
}

val platformGroup: String by project
val platformVersion: String by project
val skywalkingVersion: String by project
val gsonVersion: String by project

group = platformGroup
version = platformVersion

tasks.getByName<JavaCompile>("compileJava") {
    options.release.set(8)
    sourceCompatibility = "1.8"
}

dependencies {
    implementation(project(":protocol"))
    compileOnly(files("$projectDir/../.ext/skywalking-agent-$skywalkingVersion.jar"))
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("org.springframework:spring-expression:5.3.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:3.+")
}

tasks {
    jar {
        baseName = "spp-skywalking-services"
    }

    shadowJar {
        archiveBaseName.set("spp-skywalking-services")
        archiveClassifier.set("shadow")
        exclude("META-INF/native-image/**")
        exclude("META-INF/vertx/**")
        exclude("module-info.class")
        exclude("META-INF/maven/**")
        exclude("META-INF/services/com.fasterxml.jackson.core.JsonFactory")
        exclude("META-INF/services/com.fasterxml.jackson.core.ObjectCodec")
        exclude("META-INF/services/io.vertx.core.spi.launcher.CommandFactory")
        exclude("META-INF/services/reactor.blockhound.integration.BlockHoundIntegration")
        exclude("META-INF/services/org.apache.commons.logging.LogFactory")
        exclude("META-INF/LICENSE")
        exclude("META-INF/NOTICE")
        exclude("META-INF/license.txt")
        exclude("META-INF/notice.txt")
        exclude("META-INF/io.netty.versions.properties")
        exclude("META-INF/spring-core.kotlin_module")
        exclude("META-INF/kotlin-coroutines.kotlin_module")
        exclude("org/springframework/cglib/util/words.txt")
        exclude("org/springframework/expression/spel/generated/SpringExpressions.g")
        relocate("com.google.gson", "spp.probe.services.dependencies.com.google.gson")
        relocate("org.apache.commons", "spp.probe.services.dependencies.org.apache.commons")
        relocate("org.springframework", "spp.probe.services.dependencies.org.springframework")
    }

    build {
        if (System.getProperty("build.profile") == "debian") {
            dependsOn("shadowJar", "proguard")
        } else {
            dependsOn("shadowJar")
        }

        doLast {
            File("$buildDir/libs/spp-skywalking-services-$version.jar").delete()
        }
    }

    test {
        failFast = true
        maxParallelForks = Runtime.getRuntime().availableProcessors() / 2
    }

    create<proguard.gradle.ProGuardTask>("proguard") {
        dependsOn("shadowJar")
        configuration("proguard.conf")
        injars(File("$buildDir/libs/spp-skywalking-services-$version-shadow.jar"))
        outjars(File("$buildDir/libs/spp-skywalking-services-$version.jar"))
        libraryjars("${org.gradle.internal.jvm.Jvm.current().javaHome}/jmods")
        libraryjars(files("$projectDir/../.ext/skywalking-agent-$skywalkingVersion.jar"))

        doLast {
            File("$buildDir/libs/spp-skywalking-services-$version-shadow.jar").delete()
        }
    }
}
