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
package spp.platform.bridge

import io.vertx.core.DeploymentOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.platform.bridge.marker.MarkerBridge
import spp.platform.bridge.probe.ProbeBridge
import spp.platform.common.ClusterConnection
import spp.platform.common.ClusterConnection.config
import spp.platform.common.PlatformServices
import spp.platform.common.service.SourceBridgeService
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import kotlin.system.exitProcess

class SourceBridgeModule : ModuleDefine("spp-platform-bridge") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class SourceBridgeProvider : ModuleProvider() {
    private val log: Logger = LoggerFactory.getLogger(SourceBridgeProvider::class.java)

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = SourceBridgeModule::class.java
    override fun createConfigBeanIfAbsent(): ModuleConfig? = null
    override fun prepare() = Unit

    override fun start() {
        try {
            log.info("Starting spp-platform-bridge")
            val vertx = ClusterConnection.getVertx()
            runBlocking(vertx.dispatcher()) {
                //Open bridges
                val keyFile = File("config/spp-platform.key")
                val certFile = File("config/spp-platform.crt")
                val httpConfig = config.getJsonObject("spp-platform").getJsonObject("http")
                val sslEnabled = httpConfig.getString("ssl_enabled").toBooleanStrict()
                val netServerOptions = NetServerOptions()
                    .removeEnabledSecureTransportProtocol("SSLv2Hello")
                    .removeEnabledSecureTransportProtocol("TLSv1")
                    .removeEnabledSecureTransportProtocol("TLSv1.1")
                    .setSsl(sslEnabled)
                    .apply {
                        if (sslEnabled) {
                            pemKeyCertOptions = PemKeyCertOptions()
                                .setKeyValue(Buffer.buffer(keyFile.readText()))
                                .setCertValue(Buffer.buffer(certFile.readText()))
                        }
                    }

                val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
                val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()
                val jwt: JWTAuth?
                if (!jwtEnabled) {
                    jwt = null
                } else {
                    val keyPair = PEMParser(StringReader(keyFile.readText())).use {
                        JcaPEMKeyConverter().getKeyPair(it.readObject() as PEMKeyPair)
                    }

                    val publicKey = StringWriter()
                    var writer = JcaPEMWriter(publicKey)
                    writer.writeObject(keyPair.public)
                    withContext(Dispatchers.IO) { writer.close() }
                    val privateKey = StringWriter()
                    writer = JcaPEMWriter(privateKey)
                    writer.writeObject(keyPair.private)
                    withContext(Dispatchers.IO) { writer.close() }

                    jwt = JWTAuth.create(
                        vertx, JWTAuthOptions()
                            .addPubSecKey(
                                PubSecKeyOptions()
                                    .setAlgorithm("RS256")
                                    .setBuffer(publicKey.toString())
                            )
                            .addPubSecKey(
                                PubSecKeyOptions()
                                    .setAlgorithm("RS256")
                                    .setBuffer(privateKey.toString())
                            )
                    )
                }

                vertx.deployVerticle(
                    ProbeBridge(ClusterConnection.router, jwt, netServerOptions),
                    DeploymentOptions().setConfig(config.getJsonObject("spp-platform"))
                ).await()
                vertx.deployVerticle(
                    MarkerBridge(jwt, netServerOptions),
                    DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("marker"))
                ).await()

                //start service
                val sourceBridgeService = SourceBridge()
                vertx.deployVerticle(sourceBridgeService).await()
                ServiceBinder(vertx).setIncludeDebugInfo(true)
//                    .addInterceptor { developerAuthInterceptor(it) }
//                    .addInterceptor { msg -> permissionAndAccessCheckInterceptor(msg) }
                    .setAddress(PlatformServices.BRIDGE_SERVICE)
                    .register(SourceBridgeService::class.java, sourceBridgeService)
                val bridgeRecord = EventBusService.createRecord(
                    PlatformServices.BRIDGE_SERVICE,
                    PlatformServices.BRIDGE_SERVICE,
                    SourceBridgeService::class.java
                )
                ClusterConnection.discovery.publish(bridgeRecord) {
                    if (it.succeeded()) {
                        log.info("Bridge service published")
                    } else {
                        log.error("Failed to publish bridge service", it.cause())
                        exitProcess(-1)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to start spp-platform-bridge", e)
            exitProcess(-1)
        }
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> = arrayOf("spp-platform-core")
}
