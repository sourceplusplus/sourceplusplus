package integration;

import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spp.probe.ProbeConfiguration;
import spp.probe.SourceProbe;
import spp.protocol.probe.command.LiveInstrumentCommand;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static spp.probe.SourceProbe.vertx;
import static spp.protocol.probe.ProbeAddress.LIVE_LOG_REMOTE;
import static spp.protocol.probe.command.LiveInstrumentCommand.CommandType.ADD_LIVE_INSTRUMENT;

@ExtendWith(VertxExtension.class)
public class IntegrationTest {

    private final static Logger log = LoggerFactory.getLogger(IntegrationTest.class);

    public static final String SYSTEM_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJzeXN0ZW0iLCJjcmVhdGVkX2F0IjoxNjIyNDIxMzY0ODY4" +
                    "LCJleHBpcmVzX2F0IjoxNjUzOTU3MzY0ODY4LCJpYXQiOjE2MjI0MjEzNjR9.ZVHtxQkfCF7KM_dyDOgawbwpEAsmnCWB4c8I" +
                    "52svPvVc-SlzkEe0SYrNufNPniYZeM3IF0Gbojl_DSk2KleAz9CLRO3zfegciXKeEEvGjsNOqfQjgU5yZtBWmTimVXq5QoZME" +
                    "GuAojACaf-m4J0H7o4LQNGwrDVA-noXVE0Eu84A5HxkjrRuFlQWv3fzqSRC_-lI0zRKuFGD-JkIfJ9b_wP_OjBWT6nmqkZn_J" +
                    "mK7UwniTUJjocszSA2Ma3XLx2xVPzBcz00QWyjhIyiftxNQzgqLl1XDVkRtzXUIrHnFCR8BcgR_PsqTBn5nH7aCp16zgmkkbO" +
                    "pmJXlNpDSVz9zUY4NOrB1jTzDB190COrfCXddb7JO6fmpet9_Zd3kInJx4XsT3x7JfBSWr9FBqFoUmNkgIWjkbN1TpwMyizXA" +
                    "Sp1nOmwJ64FDIbSpfpgUAqfSWXKZYhSisfnBLEyHCjMSPzVmDh949w-W1wU9q5nGFtrx6PTOxK_WKOiWU8_oeTjL0pD8pKXqJ" +
                    "MaLW-OIzfrl3kzQNuF80YT-nxmNtp5PrcxehprlPmqSB_dyTHccsO3l63d8y9hiIzfRUgUjTJbktFn5t41ADARMs_0WMpIGZJ" +
                    "yxcVssstt4J1Gj8WUFOdqPsIKigJZMn3yshC5S-KY-7S0dVd0VXgvpPqmpb9Q9Uho";

    @Test
    public void receivePendingInstrumentsOnReconnect() throws Exception {
        VertxTestContext testContext = new VertxTestContext();
        assertTrue(SourceProbe.isAgentInitialized());
        ProbeConfiguration.setQuietMode(false);

        SourceProbe.tcpSocket.closeHandler(event -> {
            log.info("Disconnected from platform");

            String platformHost = (System.getenv("SPP_PLATFORM_HOST") != null)
                    ? System.getenv("SPP_PLATFORM_HOST") : "localhost";
            ProbeConfiguration.setString("platform_host", platformHost);

            WebClient client = WebClient.create(
                    vertx, new WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
            );

            AtomicBoolean unregistered = new AtomicBoolean(false);
            MessageConsumer<JsonObject> consumer = vertx.eventBus().localConsumer("local." + LIVE_LOG_REMOTE.getAddress());
            consumer.handler(it -> {
                log.info("Got command: {}", it.body());
                if (unregistered.get()) {
                    log.warn("Ignoring message after unregistered...");
                    return;
                }

                LiveInstrumentCommand command = LiveInstrumentCommand.fromJson(it.body().toString());

                testContext.verify(() -> {
                    assertEquals(ADD_LIVE_INSTRUMENT, command.getCommandType());
                    assertEquals(1, command.getContext().getLiveInstruments().size());


                    JsonObject liveLog = new JsonObject(command.getContext().getLiveInstruments().get(0));
                    assertEquals("test", liveLog.getString("logFormat"));
                });

                consumer.unregister(it3 -> {
                    if (it3.succeeded()) {
                        log.info("Unregistered consumer: {}", consumer.address());
                        unregistered.set(true);
                        client.post(5445, platformHost, "/graphql")
                                .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
                                .sendJsonObject(
                                        new JsonObject().put(
                                                "query",
                                                "mutation clearLiveInstruments {\n" +
                                                        "    clearLiveInstruments\n" +
                                                        "}"
                                        )
                                ).onComplete(it2 -> {
                                    if (it2.succeeded()) {
                                        log.info("Cleared live instruments");
                                        testContext.completeNow();
                                    } else {
                                        testContext.failNow(it2.cause());
                                    }
                                });
                    } else {
                        testContext.failNow(it3.cause());
                    }
                });
            }).completionHandler(it -> {
                if (it.succeeded()) {
                    log.info("Registered consumer: {}", consumer.address());
                    client.post(5445, platformHost, "/graphql")
                            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
                            .sendJsonObject(
                                    new JsonObject().put(
                                            "query",
                                            "mutation addLiveLog($input: LiveLogInput!) {\n" +
                                                    "    addLiveLog(input: $input) {\n" +
                                                    "        id\n" +
                                                    "        logFormat\n" +
                                                    "        logArguments\n" +
                                                    "        location {\n" +
                                                    "            source\n" +
                                                    "            line\n" +
                                                    "        }\n" +
                                                    "        condition\n" +
                                                    "        expiresAt\n" +
                                                    "        hitLimit\n" +
                                                    "    }\n" +
                                                    "}"
                                    ).put("variables", new JsonObject()
                                            .put("input", new JsonObject()
                                                    .put("condition", "1==2")
                                                    .put("logFormat", "test")
                                                    .put("location", new JsonObject()
                                                            .put("source", "spp.example.webapp.edge.SingleThread")
                                                            .put("line", 37))
                                            ))
                            ).onComplete(it2 -> {
                                if (it2.succeeded()) {
                                    log.info("Reconnecting to platform");
                                    SourceProbe.connectToPlatform();
                                } else {
                                    testContext.failNow(it2.cause());
                                }
                            });
                } else {
                    testContext.failNow(it.cause());
                }
            });
        });

        log.info("Disconnecting from platform");
        SourceProbe.disconnectFromPlatform();

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw new RuntimeException(testContext.causeOfFailure());
            }
        } else {
            throw new RuntimeException("Test timed out");
        }
    }
}
