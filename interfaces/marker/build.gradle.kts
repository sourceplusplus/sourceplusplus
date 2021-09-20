import org.apache.tools.ant.taskdefs.condition.Os

// Import variables from gradle.properties file
val platformGroup: String by project
val platformName: String by project
val platformVersion: String by project

group = platformGroup
version = platformVersion

val vertxVersion = ext.get("vertxVersion")
val sourceMarkerVersion = ext.get("sourceMarkerVersion")

tasks {
    getByName("clean") {
        dependsOn("cleanSourceMarker")
    }

    register<Exec>("cleanSourceMarker") {
        workingDir = File(project(":interfaces:marker").projectDir, "SourceMarker")

        if (Os.isFamily(Os.FAMILY_UNIX)) {
            commandLine("./gradlew", "clean")
        } else {
            commandLine("cmd", "/c", "gradlew.bat", "clean")
        }
    }

    register<Exec>("buildSourceMarker") {
        workingDir = File(project(":interfaces:marker").projectDir, "SourceMarker")

        if (Os.isFamily(Os.FAMILY_UNIX)) {
            commandLine("./gradlew", "buildPlugin", "-x", "test")
        } else {
            commandLine("cmd", "/c", "gradlew.bat", "buildPlugin", "-x", "test")
        }
    }

    register<Copy>("moveSourceMarker") {
        dependsOn("buildSourceMarker")

        val smZip = File(
            project(":interfaces:marker").projectDir,
            "SourceMarker/plugin/jetbrains/build/distributions/SourceMarker-$sourceMarkerVersion.zip"
        )
        from(smZip)
        into(File(buildDir, "spp-plugin"))

        doFirst {
            if (!smZip.exists()) {
                throw GradleException("Missing " + smZip.absolutePath)
            }

            File(buildDir, "spp-plugin").mkdirs()
        }
    }
    register<Copy>("unzipSourceMarker") {
        dependsOn("moveSourceMarker")

        from(zipTree("build/spp-plugin/SourceMarker-$sourceMarkerVersion.zip"))
        into(File(buildDir, "spp-plugin"))
    }
    register<Zip>("configurePlugin") {
        dependsOn("unzipSourceMarker")

        archiveFileName.set("jetbrains-$sourceMarkerVersion.jar")
        destinationDirectory.set(File(buildDir, "spp-plugin"))

        from(zipTree("build/spp-plugin/SourceMarker/lib/jetbrains-$sourceMarkerVersion.jar")) {
            exclude(
                "/plugin-configuration.json",
                "/META-INF/plugin.xml",
                "/META-INF/pluginIcon.svg",
                "/META-INF/pluginIcon_dark.svg",
                "/messages/PluginBundle.properties",
                "/messages/PluginBundle_zh.properties"
            )
        }
        into("/") {
            from(File(project(":interfaces:marker").projectDir, "plugin-configuration.json"))
        }
        into("/META-INF") {
            from(File(project(":interfaces:marker").projectDir, "plugin.xml"))
            from(File(project(":interfaces:marker").projectDir, "pluginIcon.svg"))
            from(File(project(":interfaces:marker").projectDir, "pluginIcon_dark.svg"))
        }
        into("/messages") {
            from(
                File(project(":interfaces:marker").projectDir, "PluginBundle.properties"),
                File(project(":interfaces:marker").projectDir, "PluginBundle_zh.properties")
            )
        }
    }
    register<Zip>("buildPlugin") {
        dependsOn("configurePlugin")
        File(buildDir, "spp-plugin").mkdirs()
        doFirst {
            File(buildDir, "spp-plugin").mkdirs()
        }

        archiveFileName.set("spp-plugin-${project.version}.zip")
        destinationDirectory.set(buildDir)

        from(fileTree(File(buildDir, "spp-plugin"))) {
            exclude(
                "SourceMarker-$sourceMarkerVersion.zip",
                "jetbrains-$sourceMarkerVersion.jar",
                "SourceMarker/lib/jetbrains-$sourceMarkerVersion.jar"
            )
        }
        into("SourceMarker/lib") {
            from(File(buildDir, "spp-plugin/jetbrains-$sourceMarkerVersion.jar"))
        }
    }
}
