package spp.probe;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper;
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser;
import spp.probe.control.LiveInstrumentRemote;
import spp.probe.util.NopInternalLogger;
import spp.probe.util.NopLogDelegateFactory;
import spp.protocol.platform.PlatformAddress;
import spp.protocol.probe.status.ProbeConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static spp.protocol.probe.ProbeAddress.LIVE_BREAKPOINT_REMOTE;
import static spp.protocol.probe.ProbeAddress.LIVE_LOG_REMOTE;

public class SourceProbe {

    private static final ResourceBundle BUILD = ResourceBundle.getBundle("build");
    public static File PROBE_DIRECTORY = new File((System.getProperty("os.name").toLowerCase().startsWith("mac"))
            ? "/tmp" : System.getProperty("java.io.tmpdir"), "spp-probe");
    public static Instrumentation instrumentation;
    public static Vertx vertx;
    private static final AtomicBoolean connected = new AtomicBoolean();
    public static NetSocket tcpSocket;
    public static LiveInstrumentRemote instrumentRemote;

    public static boolean isAgentInitialized() {
        return instrumentation != null;
    }

    public static void premain(String args, Instrumentation inst) throws Exception {
        if (ProbeConfiguration.isNotQuite()) System.out.println("SourceProbe initiated");

        //todo: pipe data if in debug mode
        System.setProperty("vertx.logger-delegate-factory-class-name", NopLogDelegateFactory.class.getCanonicalName());
        InternalLoggerFactory.setDefaultFactory(new InternalLoggerFactory() {
            private final NopInternalLogger nopInternalLogger = new NopInternalLogger();

            @Override
            protected InternalLogger newInstance(String name) {
                return nopInternalLogger;
            }
        });

        instrumentation = inst;
        vertx = Vertx.vertx();

        connectToPlatform();
        unzipAgent(BUILD.getString("apache_skywalking_version"));
        addAgentToClassLoader();
        configureAgent();
        invokeAgent();

        try {
            java.lang.ClassLoader agentClassLoader = (java.lang.ClassLoader) Class.forName(
                    "org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader"
            ).getMethod("getDefault").invoke(null);
            Class sizeCappedClass = Class.forName(
                    "spp.probe.services.common.serialize.SizeCappedTypeAdapterFactory", true, agentClassLoader
            );
            sizeCappedClass.getMethod("setInstrumentation", Instrumentation.class)
                    .invoke(null, instrumentation);
            sizeCappedClass.getMethod("setMaxMemorySize", long.class)
                    .invoke(null, 1024L * 1024L); //1MB
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        deployRemotes();
    }

    public static void deployRemotes() {
        vertx.deployVerticle(instrumentRemote = new LiveInstrumentRemote());
    }

    public static void disconnectFromPlatform() throws Exception {
        connected.set(false);
        tcpSocket.close();
        tcpSocket = null;
        instrumentRemote.stop();
        instrumentRemote = null;
    }

    public static synchronized void connectToPlatform() {
        if (connected.get()) return;
        NetClientOptions options;
        if (Objects.equals(System.getenv("SPP_DISABLE_TLS"), "true")
                || ProbeConfiguration.getString("platform_certificate") == null) {
            options = new NetClientOptions()
                    .setReconnectAttempts(Integer.MAX_VALUE).setReconnectInterval(5000)
                    .setSsl(false);
        } else {
            Buffer myCaAsABuffer = Buffer.buffer("-----BEGIN CERTIFICATE-----"
                    + ProbeConfiguration.getString("platform_certificate") + "-----END CERTIFICATE-----");
            options = new NetClientOptions()
                    .setReconnectAttempts(Integer.MAX_VALUE).setReconnectInterval(5000)
                    .setSsl(true)
                    .setPemTrustOptions(new PemTrustOptions().addCertValue(myCaAsABuffer));
        }
        NetClient client = vertx.createNetClient(options);
        client.connect(ProbeConfiguration.getInteger("platform_port"), ProbeConfiguration.getString("platform_host"), socket -> {
            if (socket.failed()) {
                if (ProbeConfiguration.isNotQuite()) System.err.println("Failed to connect to Source++ Platform");
                if (ProbeConfiguration.isNotQuite()) socket.cause().printStackTrace();
                connectToPlatform();
                return;
            } else {
                tcpSocket = socket.result();
                connected.set(true);
            }
            if (ProbeConfiguration.isNotQuite()) System.out.println("Connected to Source++ Platform");

            socket.result().exceptionHandler(it -> {
                connected.set(false);
                connectToPlatform();
            });
            socket.result().closeHandler(it -> {
                connected.set(false);
                connectToPlatform();
            });

            //handle platform messages
            final FrameParser parser = new FrameParser(parse -> {
                JsonObject frame = parse.result();
                if ("message".equals(frame.getString("type"))) {
                    if (frame.getString("replyAddress") != null) {
                        vertx.eventBus().request("local." + frame.getString("address"),
                                frame.getJsonObject("body")).onComplete(it -> {
                            if (it.succeeded()) {
                                FrameHelper.sendFrame(
                                        BridgeEventType.SEND.name().toLowerCase(), frame.getString("replyAddress"),
                                        JsonObject.mapFrom(it.result().body()), socket.result()
                                );
                            } else {
                                FrameHelper.sendFrame(
                                        BridgeEventType.SEND.name().toLowerCase(), frame.getString("replyAddress"),
                                        JsonObject.mapFrom(it.cause()), socket.result()
                                );
                            }
                        });
                    } else {
                        if (frame.getBoolean("send") != null && !frame.getBoolean("send")) {
                            vertx.eventBus().publish("local." + frame.getString("address"),
                                    frame.getJsonObject("body"));
                        } else {
                            vertx.eventBus().send("local." + frame.getString("address"),
                                    frame.getJsonObject("body"));
                        }
                    }
                } else {
                    throw new UnsupportedOperationException(frame.toString());
                }
            });
            socket.result().handler(parser);

            //send probe connected status
            HashMap<String, Object> meta = new HashMap<>();
            meta.put("language", "java");
            meta.put("probe_version", BUILD.getString("build_version"));
            meta.put("java_version", System.getProperty("java.version"));
            String replyAddress = UUID.randomUUID().toString();
            ProbeConnection pc = new ProbeConnection(UUID.randomUUID().toString(), System.currentTimeMillis(), meta);
            MessageConsumer<Boolean> consumer = vertx.eventBus().localConsumer("local." + replyAddress);
            consumer.handler(resp -> {
                if (ProbeConfiguration.isNotQuite()) System.out.println("Received probe connection confirmation");

                //register remotes
                FrameHelper.sendFrame(
                        BridgeEventType.REGISTER.name().toLowerCase(),
                        LIVE_BREAKPOINT_REMOTE.getAddress(),
                        new JsonObject(),
                        SourceProbe.tcpSocket
                );
                FrameHelper.sendFrame(
                        BridgeEventType.REGISTER.name().toLowerCase(),
                        LIVE_LOG_REMOTE.getAddress(),
                        new JsonObject(),
                        SourceProbe.tcpSocket
                );
                consumer.unregister();
            });
            FrameHelper.sendFrame(
                    BridgeEventType.SEND.name().toLowerCase(), PlatformAddress.PROBE_CONNECTED.getAddress(),
                    replyAddress, new JsonObject(), true, JsonObject.mapFrom(pc), socket.result()
            );
        });
    }

    private static void invokeAgent() {
        if (ProbeConfiguration.isNotQuite()) System.out.println("SourceProbe finished setup");
        try {
            Method skywalkingPremain = Class.forName("org.apache.skywalking.apm.agent.SkyWalkingAgent")
                    .getMethod("premain", String.class, Instrumentation.class);
            skywalkingPremain.invoke(null, null, instrumentation);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void configureAgent() {
        ProbeConfiguration.getSkywalkingSettings().forEach(it -> System.setProperty(it[0], it[1]));
        ProbeConfiguration.getSppSettings().forEach(it -> System.setProperty(it[0], it[1]));
    }

    private static void addAgentToClassLoader() throws Exception {
        File skywalkingAgentFile = new File(PROBE_DIRECTORY, "skywalking-agent.jar");
        java.lang.ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader dynamic = ClassLoader.findAncestor(contextClassLoader);
        if (dynamic != null) {
            dynamic.add(skywalkingAgentFile.toURI().toURL());
        } else {
            if (getJvmMajorVersion() >= 9) {
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(skywalkingAgentFile));
            } else {
                URLClassLoader classLoader = (URLClassLoader) java.lang.ClassLoader.getSystemClassLoader();
                Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                method.setAccessible(true);
                method.invoke(classLoader, skywalkingAgentFile.toURI().toURL());
            }
        }

        //org.apache.skywalking.+ must be referenced as fully qualified
        org.apache.skywalking.apm.agent.core.conf.Config.Logging.LEVEL =
                org.apache.skywalking.apm.agent.core.logging.core.LogLevel.valueOf(ProbeConfiguration.getSkyWalkingLoggingLevel());
    }

    private static void unzipAgent(String skywalkingVersion) throws IOException {
        if (!Objects.equals(System.getenv("SPP_DELETE_PROBE_DIRECTORY_ON_BOOT"), "false")) {
            deleteRecursively(PROBE_DIRECTORY);
        }
        PROBE_DIRECTORY.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(
                Objects.requireNonNull(SourceProbe.class.getClassLoader().getResourceAsStream(
                        String.format("skywalking-agent-%s.zip", skywalkingVersion)
                ))
        )) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = new File(PROBE_DIRECTORY, zipEntry.getName());
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    byte[] buffer = new byte[1024];
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    private static void deleteRecursively(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteRecursively(file);
            }
        }
        directory.delete();
    }

    private static int getJvmMajorVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }
}
