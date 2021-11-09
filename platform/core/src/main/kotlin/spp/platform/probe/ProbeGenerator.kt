package spp.platform.probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.common.io.Files
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.kohsuke.github.GitHub
import org.zeroturnaround.zip.ZipUtil
import spp.platform.probe.config.SourceProbeConfig
import spp.protocol.platform.PlatformAddress
import java.io.*
import java.net.URL
import java.security.cert.X509Certificate
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProbeGenerator : AbstractVerticle() {

    private val log = KotlinLogging.logger {}
    private val objectMapper = ObjectMapper()
    private val yamlMapper = YAMLMapper()
    private val generatedProbes = mutableMapOf<SourceProbeConfig, JsonObject>()
    private val githubApi by lazy { GitHub.connectAnonymously() }

    override fun start() {
        vertx.eventBus().consumer<SourceProbeConfig>(PlatformAddress.GENERATE_PROBE.address) {
            var config = it.body()
            val probeRelease = if (config.probeVersion == "latest") {
                val probeRelease = githubApi.getRepository("sourceplusplus/probe-jvm").latestRelease
                config = config.copy(probeVersion = probeRelease.tagName)
                probeRelease
            } else {
                githubApi.getRepository("sourceplusplus/probe-jvm").getReleaseByTagName(config.probeVersion)
            }
            if (probeRelease == null) {
                log.error { "Probe release not found: ${config.probeVersion}" }
                it.fail(404, "Probe release not found: ${config.probeVersion}")
                return@consumer
            }

            val downloadUrl = probeRelease.listAssets()
                .find { it.name.contains("spp-probe.jar") }!!.browserDownloadUrl
            val destFile = File(Files.createTempDir(), "spp-probe-${probeRelease.tagName}.jar")
            FileUtils.copyURLToFile(URL(downloadUrl), destFile)
            it.reply(generateProbe(destFile, config))
        }

        //pre-generate default configuration probe
        GlobalScope.launch(vertx.dispatcher()) {
            val platformHost = System.getenv("SPP_CLUSTER_URL")
            val platformName = System.getenv("SPP_CLUSTER_NAME")
            if (!platformHost.isNullOrEmpty() && !platformName.isNullOrEmpty()) {
                log.debug("Pre-generating default configuration probe")
                val probeRelease = githubApi.getRepository("sourceplusplus/probe-jvm").latestRelease
                val downloadUrl = probeRelease.listAssets()
                    .find { it.name.contains("spp-probe.jar") }!!.browserDownloadUrl
                val destFile = File(Files.createTempDir(), "spp-probe-${probeRelease.tagName}.jar")
                FileUtils.copyURLToFile(URL(downloadUrl), destFile)

                val config = SourceProbeConfig(platformHost, platformName, probeVersion = probeRelease.tagName)
                generateProbe(destFile, config)
            } else {
                log.warn("Skipped pre-generating default configuration probe")
            }
        }
    }

    private fun generateProbe(baseProbe: File, config: SourceProbeConfig): JsonObject {
        val existingProbe = generatedProbes[config]
        if (existingProbe != null && File(existingProbe.getString("file_location")).exists()) {
            return existingProbe
        }

        generatedProbes.remove(config)
        val crtFileData = File("config/spp-platform.crt").readText()
        val crtParser = PEMParser(StringReader(crtFileData))
        val crtHolder = crtParser.readObject() as X509CertificateHolder
        val certificate = JcaX509CertificateConverter().getCertificate(crtHolder)
        val probePath = generateProbe(baseProbe, config, certificate)
        val cache = JsonObject().put("probe_version", config.probeVersion).put("file_location", probePath.absolutePath)
        generatedProbes[config] = cache
        return cache
    }

    private fun generateProbe(baseProbe: File, config: SourceProbeConfig, certificate: X509Certificate): File {
        val tempDir = Files.createTempDir()
        unzip(baseProbe, tempDir)

        val crt = StringWriter()
        val writer = JcaPEMWriter(crt)
        writer.writeObject(certificate)
        writer.close()

        val jsonObject = JsonObject()
            .put(
                "spp", JsonObject()
                    .put("platform_host", config.platformHost)
                    .put("platform_port", config.platformPort)
                    .apply {
                        if (System.getenv("SPP_DISABLE_JWT") != "true") {
                            put(
                                "platform_certificate", crt.toString()
                                    .replace("-----BEGIN CERTIFICATE-----", "")
                                    .replace("-----END CERTIFICATE-----", "")
                                    .replace("\n", "")
                            )
                        }
                    }
            ).put(
                "skywalking", JsonObject()
                    .put(
                        "logging", JsonObject()
                            .put("level", "WARN")
                    )
                    .put(
                        "agent", JsonObject()
                            .put("service_name", config.skywalkingServiceName)
                            .put("is_cache_enhanced_class", true)
                            .put("class_cache_mode", "FILE")
                    )
                    .put(
                        "collector", JsonObject()
                            .put("backend_service", config.skywalkingBackendService)
                    )
            )

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
}
