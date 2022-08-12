import java.util.*

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("com.github.johnrengelman.shadow")
}

val platformGroup: String by project
val projectVersion: String by project

group = platformGroup
version = project.properties["platformVersion"] as String? ?: projectVersion

configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sourceplusplus/live-platform")
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
                artifactId = "platform-core"
                version = project.version.toString()

                from(components["kotlin"])
            }
        }
    }
}

dependencies {
    compileOnly(project(":platform:storage"))
    implementation(project(":platform:common"))

    //todo: properly add test dependency
    testImplementation(project(":platform:common").dependencyProject.extensions.getByType(SourceSetContainer::class).test.get().output)
}

//todo: shouldn't need to put in src (GitHub actions needs for some reason)
tasks.create("createProperties") {
    if (System.getProperty("build.profile") == "release") {
        val buildBuildFile = File(projectDir, "src/main/resources/build.properties")
        if (buildBuildFile.exists()) {
            buildBuildFile.delete()
        } else {
            buildBuildFile.parentFile.mkdirs()
        }

        buildBuildFile.writer().use {
            val p = Properties()
            p["build_id"] = UUID.randomUUID().toString()
            p["build_date"] = Date().toInstant().toString()
            p["build_version"] = project.version.toString()
            p.store(it, null)
        }
    }
}
tasks["processResources"].dependsOn("createProperties")

tasks.getByName<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("spp-platform-core")
    archiveClassifier.set("")
    manifest {
        attributes(
            mapOf(
                "Main-Class" to "spp.platform.SourcePlatform"
            )
        )
    }

    minimize {
        exclude(dependency("com.fasterxml.jackson.datatype:.*:.*"))
        exclude(dependency("plus.sourceplus:protocol:.*"))
        exclude(dependency("org.bouncycastle:.*:.*"))
        exclude(dependency("io.vertx:.*:.*"))
        exclude(dependency("org.jetbrains.kotlinx:.*:.*"))
        exclude(dependency("com.fasterxml.jackson.dataformat:.*:.*"))
        exclude(dependency("org.jooq:joor:.*"))
        exclude(dependency("org.kohsuke:github-api:.*"))
        exclude(project(":platform:bridge"))
        exclude(project(":platform:common"))
        exclude(project(":platform:core"))
    }

    exclude("spp/platform/storage/**")
    exclude("ch/qos/**")
    exclude("org/slf4j/**")
    exclude("logback.xml")
    exclude("**/logback.xml")
    exclude("**/*.proto")
    exclude("**/*.kotlin_module")
    exclude("META-INF/native-image/**")
    exclude("META-INF/vertx/**")
    exclude("module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/LICENSE")
    exclude("META-INF/NOTICE")
    exclude("META-INF/license.txt")
    exclude("META-INF/notice.txt")
    exclude("META-INF/io.netty.versions.properties")
    exclude("org/springframework/cglib/util/words.txt")
    exclude("org/springframework/expression/spel/generated/SpringExpressions.g")
    relocate("com.codahale", "spp.platform.dependencies.com.codahale")
    relocate("io.netty", "spp.platform.dependencies.io.netty")

    configurations.add(project.configurations.compileClasspath.get())
    configurations.add(project.configurations.runtimeClasspath.get())
    configurations.add(project.configurations.shadow.get())
}
tasks.getByName("assemble").dependsOn("shadowJar")
