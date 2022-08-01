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
package spp.platform.bridge.probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.file.AsyncFile
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonObject
import io.vertx.core.streams.WriteStream
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.kohsuke.github.GitHub
import spp.platform.storage.SourceStorage
import spp.protocol.marshall.LocalMessageCodec
import spp.protocol.platform.auth.ClientAccess
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProbeGenerator(private val router: Router) : CoroutineVerticle() {

    companion object {
        private const val LOCAL_GEN_PROBE_YML_ADDR = "local.generate-spp-probe.yml"
        private const val LOCAL_GEN_JVM_PROBE_ADDR = "local.generate-jvm-probe.jar"
        private val log = KotlinLogging.logger {}
        private val objectMapper = ObjectMapper()
        private val yamlMapper = YAMLMapper()
        private val githubApi by lazy { GitHub.connectAnonymously() }
    }

    private data class Request(
        val route: RoutingContext,
        val clientAccess: ClientAccess? = null
    )

    override suspend fun start() {
        router["/download/spp-probe.yml"].handler { route ->
            val jwtEnabled = config.getJsonObject("jwt").getString("enabled").toBooleanStrict()
            if (!jwtEnabled) {
                vertx.eventBus().send(LOCAL_GEN_PROBE_YML_ADDR, route, DeliveryOptions().setLocalOnly(true))
                return@handler
            }

            val token = route.request().getParam("access_token")
            if (token == null) {
                route.response().setStatusCode(401).end("Unauthorized")
                return@handler
            }

            log.info("spp-probe.yml download request. Verifying access token: {}", token)
            launch(vertx.dispatcher()) {
                val clientAccess = SourceStorage.getClientAccessors().firstOrNull()
                SourceStorage.getDeveloperByAccessToken(token)?.let {
                    vertx.eventBus().send(
                        LOCAL_GEN_PROBE_YML_ADDR,
                        Request(route, clientAccess),
                        DeliveryOptions().setLocalOnly(true)
                    )
                } ?: route.response().setStatusCode(401).end("Unauthorized")
            }
        }
        router["/download/jvm-probe.jar"].handler { route ->
            val jwtEnabled = config.getJsonObject("jwt").getString("enabled").toBooleanStrict()
            if (!jwtEnabled) {
                vertx.eventBus().send(LOCAL_GEN_JVM_PROBE_ADDR, route, DeliveryOptions().setLocalOnly(true))
                return@handler
            }

            val token = route.request().getParam("access_token")
            if (token == null) {
                route.response().setStatusCode(401).end("Unauthorized")
                return@handler
            }

            log.info("jvm-probe.jar download request. Verifying access token: {}", token)
            launch(vertx.dispatcher()) {
                val clientAccess = SourceStorage.getClientAccessors().firstOrNull()
                SourceStorage.getDeveloperByAccessToken(token)?.let {
                    vertx.eventBus().send(
                        LOCAL_GEN_JVM_PROBE_ADDR,
                        Request(route, clientAccess),
                        DeliveryOptions().setLocalOnly(true)
                    )
                } ?: route.response().setStatusCode(401).end("Unauthorized")
            }
        }

        vertx.eventBus().registerDefaultCodec(Request::class.java, LocalMessageCodec())
        vertx.eventBus().localConsumer<Request>(LOCAL_GEN_PROBE_YML_ADDR) { msg ->
            val crtFile = File("config/spp-platform.crt")
            val certificate = if (crtFile.exists()) {
                val crtParser = PEMParser(StringReader(crtFile.readText()))
                val crtHolder = crtParser.readObject() as X509CertificateHolder
                JcaX509CertificateConverter().getCertificate(crtHolder)
            } else {
                null
            }

            val platformHost = msg.body().route.request().host().substringBefore(":")
            val serviceName = msg.body().route.request().getParam("service_name")?.toString() ?: "Your_ApplicationName"
            val config = SourceProbeConfig(platformHost, serviceName, probeVersion = "latest")
            val yamlStr = yamlMapper.writeValueAsString(
                objectMapper.readTree(
                    JsonObject.mapFrom(getMinimumProbeConfig(config, certificate, msg.body().clientAccess)).toString()
                )
            )
            msg.body().route.response()
                .putHeader("Content-Type", "application/x-yaml")
                .putHeader("content-disposition", "attachment; filename=spp-probe.yml")
                .end(yamlStr)
        }
        vertx.eventBus().localConsumer<Request>(LOCAL_GEN_JVM_PROBE_ADDR) { msg ->
            doJVMProbeGeneration(msg.body().route, msg.body().clientAccess)
        }
    }

    private fun doJVMProbeGeneration(route: RoutingContext, clientAccess: ClientAccess?) {
        log.debug("Generating signed JVM probe")
        val platformHost = route.request().host().substringBefore(":")
        val serviceName = route.request().getParam("service_name")?.toString() ?: "Your_ApplicationName"
        val probeVersion = route.request().getParam("version")
        var config = if (!probeVersion.isNullOrEmpty()) {
            SourceProbeConfig(platformHost, serviceName, probeVersion = probeVersion)
        } else {
            SourceProbeConfig(platformHost, serviceName, probeVersion = "latest")
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
        val destFile = File("cache", "spp-probe-${probeRelease.tagName}.jar")
        route.response().putHeader(
            "content-disposition",
            "attachment; filename=spp-probe-${probeRelease.tagName}.jar"
        ).setChunked(true)

        val fileStreams = if (!destFile.exists()) {
            destFile.parentFile.mkdirs()
            log.info("Saving cached probe to: ${destFile.absolutePath}")

            //pipe modified probe to user while caching original for future use
            log.info("Downloading probe from: $downloadUrl")
            val zis = ZipInputStream(URL(downloadUrl).openStream())
            val tempFile = File.createTempFile("spp-probe", ".jar")
            Triple(zis, OutputWriterStream(route.response()), tempFile)
        } else {
            log.info("Probe already generated. Using cached probe")
            Triple(ZipInputStream(destFile.inputStream()), OutputWriterStream(route.response()), null)
        }

        val crtFile = File("config/spp-platform.crt")
        if (crtFile.exists()) {
            val crtParser = PEMParser(StringReader(crtFile.readText()))
            val crtHolder = crtParser.readObject() as X509CertificateHolder
            val certificate = JcaX509CertificateConverter().getCertificate(crtHolder)
            generateProbe(fileStreams.first, fileStreams.second, fileStreams.third, config, certificate, clientAccess)
        } else {
            generateProbe(fileStreams.first, fileStreams.second, fileStreams.third, config, null, clientAccess)
        }
        log.info("Signed probe downloaded")
    }

    @Throws(IOException::class)
    private fun generateProbe(
        zin: ZipInputStream,
        responseStream: OutputStream,
        tempFile: File? = null,
        probeConfig: SourceProbeConfig,
        certificate: X509Certificate?,
        clientAccess: ClientAccess?
    ) {
        val buffer = ByteArray(8192)
        val responseOut = ZipOutputStream(responseStream)
        val cacheStream = tempFile?.let { tempFile.outputStream() }
        val cacheOut = cacheStream?.let { ZipOutputStream(it) }
        var entry = zin.nextEntry
        while (entry != null) {
            val name = entry.name
            responseOut.putNextEntry(ZipEntry(name))
            cacheOut?.putNextEntry(ZipEntry(name))

            var len: Int
            while (zin.read(buffer).also { len = it } > 0) {
                responseOut.write(buffer, 0, len)
                cacheOut?.write(buffer, 0, len)
            }
            entry = zin.nextEntry
        }
        zin.close()
        cacheOut?.close()
        tempFile?.let {
            Files.move(
                it.toPath(),
                File("cache", "spp-probe-${probeConfig.probeVersion}.jar").toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        //write minimum probe config as spp-probe.yml inside probe jar
        val yamlStr = yamlMapper.writeValueAsString(
            objectMapper.readTree(
                JsonObject.mapFrom(getMinimumProbeConfig(probeConfig, certificate, clientAccess)).toString()
            )
        )
        responseOut.putNextEntry(ZipEntry("spp-probe.yml"))
        yamlStr.byteInputStream().use {
            var len: Int
            while (it.read(buffer).also { len = it } > 0) {
                responseOut.write(buffer, 0, len)
            }
            responseOut.closeEntry()
        }

        responseOut.close()
    }

    private fun getMinimumProbeConfig(
        probeConfig: SourceProbeConfig,
        certificate: X509Certificate?,
        clientAccess: ClientAccess?
    ): MutableMap<String, MutableMap<Any, Any>> {
        //determine minimum probe config
        val minProbeConfig = mutableMapOf<String, MutableMap<Any, Any>>(
            "spp" to mutableMapOf(
                "platform_host" to probeConfig.platformHost,
                "platform_port" to probeConfig.platformPort
            ),
            "skywalking" to mutableMapOf(
                "agent" to mutableMapOf(
                    "service_name" to probeConfig.serviceName,
                )
            )
        )
        if (certificate != null) {
            val crt = StringWriter()
            val writer = JcaPEMWriter(crt)
            writer.writeObject(certificate)
            writer.close()

            //add certificate data to minimum config
            minProbeConfig["spp"]!!["platform_certificate"] = crt.toString()
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\n", "")
        }

        //add client access (if necessary)
        clientAccess?.let {
            minProbeConfig["spp"]!!["authentication"] = mutableMapOf(
                "client_id" to it.id,
                "client_secret" to it.secret
            )
        }

        return minProbeConfig
    }

    private class OutputWriterStream(response: WriteStream<Buffer>) : OutputStream() {
        @Synchronized
        @Throws(IOException::class)
        override fun write(b: Int) {
            buffer[counter++] = b.toByte()
            if (counter >= buffer.size) {
                flush()
            }
        }

        @Throws(IOException::class)
        override fun flush() {
            super.flush()
            if (counter > 0) {
                var remaining = buffer
                if (counter < buffer.size) {
                    remaining = ByteArray(counter)
                    System.arraycopy(buffer, 0, remaining, 0, counter)
                }
                response.write(Buffer.buffer(remaining))
                counter = 0
            }
        }

        @Throws(IOException::class)
        override fun close() {
            flush()
            super.close()
            if (response is HttpServerResponse) {
                try {
                    response.end()
                } catch (ignore: IllegalStateException) {
                }
            } else if (response is AsyncFile) {
                response.close()
            }
        }

        private val response: WriteStream<Buffer>
        private val buffer: ByteArray
        private var counter = 0

        init {
            this.response = response
            buffer = ByteArray(8192)
        }
    }

    private data class SourceProbeConfig(
        val platformHost: String,
        val serviceName: String,
        val skywalkingBackendService: String = "$platformHost:11800",
        val platformPort: Int = 5450,
        val probeVersion: String
    )
}
