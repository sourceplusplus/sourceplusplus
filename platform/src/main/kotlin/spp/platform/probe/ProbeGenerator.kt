/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.platform.probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.common.io.Files
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.commons.io.IOUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.kohsuke.github.GitHub
import org.zeroturnaround.zip.ZipUtil
import spp.platform.core.SourceStorage
import java.io.*
import java.net.URL
import java.nio.channels.Channels
import java.security.cert.X509Certificate
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProbeGenerator(private val router: Router) : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val objectMapper = ObjectMapper()
    private val yamlMapper = YAMLMapper()
    private val generatedProbes = mutableMapOf<SourceProbeConfig, JsonObject>()
    private val githubApi by lazy { GitHub.connectAnonymously() }

    override suspend fun start() {
        router["/download/spp-probe"].handler { route ->
            if (System.getenv("SPP_DISABLE_JWT") == "true") {
                doProbeGeneration(route)
                return@handler
            }

            val token = route.request().getParam("access_token")
            if (token == null) {
                route.response().setStatusCode(401).end("Unauthorized")
                return@handler
            }

            log.info("Probe download request. Verifying access token: {}", token)
            launch(vertx.dispatcher()) {
                SourceStorage.getDeveloperByAccessToken(token)?.let {
                    doProbeGeneration(route)
                } ?: route.response().setStatusCode(401).end("Unauthorized")
            }
        }
    }

    private fun doProbeGeneration(route: RoutingContext) {
        log.debug("Generating signed probe")
        val platformHost = route.request().host().substringBefore(":")
        val platformName = route.request().getParam("service_name")?.toString() ?: "Your_ApplicationName"
        val probeVersion = route.request().getParam("version")
        var config = if (!probeVersion.isNullOrEmpty()) {
            SourceProbeConfig(platformHost, platformName, probeVersion = probeVersion)
        } else {
            SourceProbeConfig(platformHost, platformName, probeVersion = "latest")
        }

        val probeRelease = if (config.probeVersion == "latest") {
            val probeRelease = githubApi.getRepository("sourceplusplus/probe-jvm").latestRelease
            config = config.copy(probeVersion = probeRelease.tagName)
            probeRelease
        } else {
            githubApi.getRepository("sourceplusplus/probe-jvm").getReleaseByTagName(config.probeVersion)
        }
        if (probeRelease == null) {
            log.error { "Probe release not found: ${config.probeVersion}" }
            route.response().setStatusCode(404).end("Probe release not found: ${config.probeVersion}")
            return
        }

        val downloadUrl = probeRelease.listAssets()
            .find { it.name.equals("spp-probe-${probeRelease.tagName}.jar") }!!.browserDownloadUrl
        val destFile = File(Files.createTempDir(), "spp-probe-${probeRelease.tagName}.jar")
        vertx.executeBlocking<Nothing> {
            if (generatedProbes[config] == null) {
                log.info("Downloading probe from: $downloadUrl")
                Channels.newChannel(URL(downloadUrl).openStream()).use { readableByteChannel ->
                    FileOutputStream(destFile).use { fileOutputStream ->
                        fileOutputStream.channel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE)
                    }
                }
            } else {
                log.info("Probe already generated. Using cached probe")
            }

            val genProbe = generateProbe(destFile, config)
            route.response().putHeader(
                "content-disposition",
                "attachment; filename=spp-probe-${genProbe.getString("probe_version")}.jar"
            ).sendFile(genProbe.getString("file_location"))
            log.info("Signed probe downloaded")

            it.complete()
        }
    }

    private fun generateProbe(baseProbe: File, config: SourceProbeConfig): JsonObject {
        val existingProbe = generatedProbes[config]
        if (existingProbe != null && File(existingProbe.getString("file_location")).exists()) {
            return existingProbe
        }

        generatedProbes.remove(config)
        val crtFile = File("config/spp-platform.crt")
        val probePath = if (crtFile.exists()) {
            val crtParser = PEMParser(StringReader(crtFile.readText()))
            val crtHolder = crtParser.readObject() as X509CertificateHolder
            val certificate = JcaX509CertificateConverter().getCertificate(crtHolder)
            generateProbe(baseProbe, config, certificate)
        } else {
            generateProbe(baseProbe, config, null)
        }
        val cache = JsonObject().put("probe_version", config.probeVersion).put("file_location", probePath.absolutePath)
        generatedProbes[config] = cache
        return cache
    }

    private fun generateProbe(baseProbe: File, config: SourceProbeConfig, certificate: X509Certificate?): File {
        val tempDir = Files.createTempDir()
        unzip(baseProbe, tempDir)

        val crt = StringWriter()
        if (certificate != null) {
            val writer = JcaPEMWriter(crt)
            writer.writeObject(certificate)
            writer.close()
        }

        val minProbeConfig = mutableMapOf<String, MutableMap<Any, Any>>(
            "spp" to mutableMapOf(
                "platform_host" to config.platformHost,
                "platform_port" to config.platformPort
            ),
            "skywalking" to mutableMapOf(
                "agent" to mutableMapOf(
                    "service_name" to config.skywalkingServiceName,
                )
            )
        )
        if (certificate != null) {
            minProbeConfig["spp"]!!["platform_certificate"] = crt.toString()
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\n", "")
        }
        val jsonObject = JsonObject.mapFrom(minProbeConfig)

        //load build.properties
        val buildProps = Properties()
        buildProps.load(FileReader(File(tempDir, "build.properties")))

        if (System.getenv("SPP_DISABLE_JWT") != "true") {
            //add ca.crt
            val archiveZip = File(tempDir, "skywalking-agent-${buildProps["apache_skywalking_version"]}.zip")
            File(tempDir, "ca.crt").writeText(crt.toString())
            ZipUtil.addEntry(archiveZip, "ca/ca.crt", File(tempDir, "ca.crt"))
        }

        //add spp-probe.yml
        val yamlStr = yamlMapper.writeValueAsString(objectMapper.readTree(jsonObject.toString()))
        File(tempDir, "spp-probe.yml").writeText(yamlStr.substring(yamlStr.indexOf("\n") + 1))

        val fos = FileOutputStream(File(tempDir, "spp-probe-${config.probeVersion}.jar"))
        val zipOut = ZipOutputStream(fos)
        for (childFile in File(tempDir.absolutePath).listFiles()) {
            zipFile(
                childFile, childFile.name, zipOut,
                setOf("spp-probe-${config.probeVersion}.jar", "ca.crt")
            )
        }
        zipOut.close()
        fos.close()

        return File(tempDir.absolutePath + "/spp-probe-${config.probeVersion}.jar")
    }

    private fun unzip(archive: File, tempDir: File) {
        ZipFile(archive).use { zipFile ->
            val entries: Enumeration<out ZipEntry> = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryDestination = File(tempDir.absolutePath, entry.name)
                if (entry.isDirectory) {
                    entryDestination.mkdirs()
                } else {
                    entryDestination.parentFile.mkdirs()
                    zipFile.getInputStream(entry).use { `in` ->
                        FileOutputStream(entryDestination).use { out ->
                            IOUtils.copy(`in`, out)
                        }
                    }
                }
            }
        }
    }

    private fun zipFile(fileToZip: File, fileName: String, zipOut: ZipOutputStream, excludeList: Set<String>) {
        if (fileToZip.isHidden) return
        if (fileToZip.isDirectory) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(ZipEntry(fileName))
                zipOut.closeEntry()
            } else {
                zipOut.putNextEntry(ZipEntry("$fileName/"))
                zipOut.closeEntry()
            }
            val children = fileToZip.listFiles()
            for (childFile in children) {
                zipFile(childFile, fileName + "/" + childFile.name, zipOut, excludeList)
            }
            return
        } else if (excludeList.any { fileName.endsWith(it) }) {
            return
        }

        val fis = FileInputStream(fileToZip)
        val zipEntry = ZipEntry(fileName)
        zipOut.putNextEntry(zipEntry)
        val bytes = ByteArray(1024)
        var length: Int
        while (fis.read(bytes).also { length = it } >= 0) {
            zipOut.write(bytes, 0, length)
        }
        fis.close()
    }

    data class SourceProbeConfig(
        val platformHost: String,
        val skywalkingServiceName: String,
        val skywalkingBackendService: String = "$platformHost:11800",
        val platformPort: Int = 5450,
        val probeVersion: String
    )
}
