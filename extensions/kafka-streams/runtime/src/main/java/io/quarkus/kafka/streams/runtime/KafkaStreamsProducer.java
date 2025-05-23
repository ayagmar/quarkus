package io.quarkus.kafka.streams.runtime;

import static io.quarkus.kafka.client.runtime.KafkaRuntimeConfigProducer.TLS_CONFIG_NAME_KEY;
import static io.quarkus.kafka.streams.runtime.KafkaStreamsRuntimeConfig.DEFAULT_KAFKA_BROKER;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KafkaStreams.StateListener;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.processor.StateRestoreListener;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;

/**
 * Manages the lifecycle of a Kafka Streams pipeline. If there's a producer
 * method returning a KS {@link Topology}, then this topology will be configured
 * and started. Optionally, before starting the pipeline, this manager will wait
 * for a given set of topics to be created, as KS itself will fail without all
 * input topics being created upfront.
 */
@Singleton
public class KafkaStreamsProducer {

    private static final Logger LOGGER = Logger.getLogger(KafkaStreamsProducer.class.getName());

    private final ExecutorService executorService;
    private final StreamsConfig streamsConfig;
    private final KafkaStreams kafkaStreams;
    private final KafkaStreamsTopologyManager topologyManager;
    private final Admin kafkaAdminClient;

    @Inject
    public KafkaStreamsProducer(KafkaStreamsSupport kafkaStreamsSupport, KafkaStreamsRuntimeConfig runtimeConfig,
            ExecutorService executorService,
            Instance<Topology> topology, Instance<KafkaClientSupplier> kafkaClientSupplier,
            @Identifier("default-kafka-broker") Instance<Map<String, Object>> defaultConfiguration,
            Instance<StateListener> stateListener, Instance<StateRestoreListener> globalStateRestoreListener,
            Instance<StreamsUncaughtExceptionHandler> uncaughtExceptionHandlerListener) {
        // No producer for Topology -> nothing to do
        if (topology.isUnsatisfied()) {
            LOGGER.warn("No Topology producer; Kafka Streams will not be started");
            this.executorService = null;
            this.streamsConfig = null;
            this.kafkaStreams = null;
            this.topologyManager = null;
            this.kafkaAdminClient = null;
            return;
        }

        Properties buildTimeProperties = kafkaStreamsSupport.getProperties();

        String bootstrapServersConfig = asString(runtimeConfig.bootstrapServers());
        if (DEFAULT_KAFKA_BROKER.equalsIgnoreCase(bootstrapServersConfig)) {
            // Try to see if kafka.bootstrap.servers is set, if so, use that value, if not, keep localhost:9092
            bootstrapServersConfig = ConfigProvider.getConfig().getOptionalValue("kafka.bootstrap.servers", String.class)
                    .orElse(bootstrapServersConfig);
        }
        Map<String, Object> cfg = Collections.emptyMap();
        if (!defaultConfiguration.isUnsatisfied()) {
            cfg = defaultConfiguration.get();
        }
        Properties kafkaStreamsProperties = getStreamsProperties(buildTimeProperties, cfg, bootstrapServersConfig,
                runtimeConfig);
        this.kafkaAdminClient = Admin.create(getAdminClientConfig(kafkaStreamsProperties));

        this.executorService = executorService;
        this.streamsConfig = new StreamsConfig(kafkaStreamsProperties);
        this.kafkaStreams = initializeKafkaStreams(streamsConfig, topology.get(),
                kafkaClientSupplier, stateListener, globalStateRestoreListener, uncaughtExceptionHandlerListener);
        this.topologyManager = new KafkaStreamsTopologyManager(kafkaAdminClient, topology.get(), runtimeConfig);
    }

    public void onStartup(@Observes StartupEvent event, Event<KafkaStreams> kafkaStreamsEvent) {
        if (kafkaStreams != null) {
            kafkaStreamsEvent.fire(kafkaStreams);
            executorService.execute(() -> {
                try {
                    topologyManager.waitForTopicsToBeCreated();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!topologyManager.isClosed()) {
                    LOGGER.debug("Starting Kafka Streams pipeline");
                    kafkaStreams.start();
                }
            });
        }
    }

    @Produces
    @Singleton
    @Unremovable
    public KafkaStreams getKafkaStreams() {
        return kafkaStreams;
    }

    @Produces
    @Singleton
    @Unremovable
    public StreamsConfig getStreamsConfig() {
        return streamsConfig;
    }

    @Produces
    @Singleton
    @Unremovable
    public KafkaStreamsTopologyManager kafkaStreamsTopologyManager() {
        return topologyManager;
    }

    void onStop(@Observes ShutdownEvent event) {
        if (topologyManager != null) {
            topologyManager.close();
        }
        if (kafkaStreams != null) {
            LOGGER.debug("Stopping Kafka Streams pipeline");
            kafkaStreams.close();
        }
        if (kafkaAdminClient != null) {
            kafkaAdminClient.close(Duration.ZERO);
        }
    }

    private static KafkaStreams initializeKafkaStreams(StreamsConfig streamsConfig, Topology topology,
            Instance<KafkaClientSupplier> kafkaClientSupplier,
            Instance<StateListener> stateListener, Instance<StateRestoreListener> globalStateRestoreListener,
            Instance<StreamsUncaughtExceptionHandler> uncaughtExceptionHandlerListener) {
        KafkaStreams kafkaStreams;
        if (kafkaClientSupplier.isUnsatisfied()) {
            kafkaStreams = new KafkaStreams(topology, streamsConfig);
        } else {
            kafkaStreams = new KafkaStreams(topology, streamsConfig, kafkaClientSupplier.get());
        }

        if (!stateListener.isUnsatisfied()) {
            kafkaStreams.setStateListener(stateListener.get());
        }
        if (!globalStateRestoreListener.isUnsatisfied()) {
            kafkaStreams.setGlobalStateRestoreListener(globalStateRestoreListener.get());
        }
        if (!uncaughtExceptionHandlerListener.isUnsatisfied()) {
            kafkaStreams.setUncaughtExceptionHandler(uncaughtExceptionHandlerListener.get());
        }

        return kafkaStreams;
    }

    /**
     * Returns all properties to be passed to Kafka Streams.
     */
    private static Properties getStreamsProperties(Properties properties,
            Map<String, Object> cfg, String bootstrapServersConfig,
            KafkaStreamsRuntimeConfig runtimeConfig) {
        Properties streamsProperties = new Properties();

        // build-time options
        streamsProperties.putAll(properties);

        // default configuration
        streamsProperties.putAll(cfg);

        // dynamic add -- back-compatibility
        streamsProperties.putAll(KafkaStreamsPropertiesUtil.quarkusKafkaStreamsProperties());
        streamsProperties.putAll(KafkaStreamsPropertiesUtil.appKafkaStreamsProperties());

        // add runtime options
        streamsProperties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersConfig);
        streamsProperties.put(StreamsConfig.APPLICATION_ID_CONFIG, runtimeConfig.applicationId());

        // app id
        if (runtimeConfig.applicationServer().isPresent()) {
            streamsProperties.put(StreamsConfig.APPLICATION_SERVER_CONFIG, runtimeConfig.applicationServer().get());
        }

        // schema registry
        if (runtimeConfig.schemaRegistryUrl().isPresent()) {
            streamsProperties.put(runtimeConfig.schemaRegistryKey(), runtimeConfig.schemaRegistryUrl().get());
        }

        // set the security protocol (in case we are doing PLAIN_TEXT)
        setProperty(runtimeConfig.securityProtocol(), streamsProperties, CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);

        // sasl
        SaslConfig sc = runtimeConfig.sasl();
        if (sc != null) {
            setProperty(sc.mechanism(), streamsProperties, SaslConfigs.SASL_MECHANISM);

            setProperty(sc.jaasConfig(), streamsProperties, SaslConfigs.SASL_JAAS_CONFIG);

            setProperty(sc.clientCallbackHandlerClass(), streamsProperties, SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS);

            setProperty(sc.loginCallbackHandlerClass(), streamsProperties, SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS);
            setProperty(sc.loginClass(), streamsProperties, SaslConfigs.SASL_LOGIN_CLASS);

            setProperty(sc.kerberosServiceName(), streamsProperties, SaslConfigs.SASL_KERBEROS_SERVICE_NAME);
            setProperty(sc.kerberosKinitCmd(), streamsProperties, SaslConfigs.SASL_KERBEROS_KINIT_CMD);
            setProperty(sc.kerberosTicketRenewWindowFactor(), streamsProperties,
                    SaslConfigs.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR);
            setProperty(sc.kerberosTicketRenewJitter(), streamsProperties, SaslConfigs.SASL_KERBEROS_TICKET_RENEW_JITTER);
            setProperty(sc.kerberosMinTimeBeforeRelogin(), streamsProperties,
                    SaslConfigs.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN);

            setProperty(sc.loginRefreshWindowFactor(), streamsProperties, SaslConfigs.SASL_LOGIN_REFRESH_WINDOW_FACTOR);
            setProperty(sc.loginRefreshWindowJitter(), streamsProperties, SaslConfigs.SASL_LOGIN_REFRESH_WINDOW_JITTER);

            setProperty(sc.loginRefreshMinPeriod(), streamsProperties, SaslConfigs.SASL_LOGIN_REFRESH_MIN_PERIOD_SECONDS,
                    DurationToSecondsFunction.INSTANCE);
            setProperty(sc.loginRefreshBuffer(), streamsProperties, SaslConfigs.SASL_LOGIN_REFRESH_BUFFER_SECONDS,
                    DurationToSecondsFunction.INSTANCE);
        }

        // ssl
        SslConfig ssl = runtimeConfig.ssl();
        if (ssl != null) {
            setProperty(ssl.protocol(), streamsProperties, SslConfigs.SSL_PROTOCOL_CONFIG);
            setProperty(ssl.provider(), streamsProperties, SslConfigs.SSL_PROVIDER_CONFIG);
            setProperty(ssl.cipherSuites(), streamsProperties, SslConfigs.SSL_CIPHER_SUITES_CONFIG);
            setProperty(ssl.enabledProtocols(), streamsProperties, SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG);

            setTrustStoreConfig(ssl.truststore(), streamsProperties);
            setKeyStoreConfig(ssl.keystore(), streamsProperties);
            setKeyConfig(ssl.key(), streamsProperties);

            setProperty(ssl.keymanagerAlgorithm(), streamsProperties, SslConfigs.SSL_KEYMANAGER_ALGORITHM_CONFIG);
            setProperty(ssl.trustmanagerAlgorithm(), streamsProperties, SslConfigs.SSL_TRUSTMANAGER_ALGORITHM_CONFIG);
            Optional<String> eia = Optional.of(ssl.endpointIdentificationAlgorithm().orElse(""));
            setProperty(eia, streamsProperties, SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG);
            setProperty(ssl.secureRandomImplementation(), streamsProperties,
                    SslConfigs.SSL_SECURE_RANDOM_IMPLEMENTATION_CONFIG);
        }

        return streamsProperties;
    }

    private static void setTrustStoreConfig(TrustStoreConfig tsc, Properties properties) {
        if (tsc != null) {
            setProperty(tsc.type(), properties, SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG);
            setProperty(tsc.location(), properties, SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
            setProperty(tsc.password(), properties, SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);
            setProperty(tsc.certificates(), properties, SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG);
        }
    }

    private static void setKeyStoreConfig(KeyStoreConfig ksc, Properties properties) {
        if (ksc != null) {
            setProperty(ksc.type(), properties, SslConfigs.SSL_KEYSTORE_TYPE_CONFIG);
            setProperty(ksc.location(), properties, SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG);
            setProperty(ksc.password(), properties, SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG);
            setProperty(ksc.key(), properties, SslConfigs.SSL_KEYSTORE_KEY_CONFIG);
            setProperty(ksc.certificateChain(), properties, SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG);
        }
    }

    private static void setKeyConfig(KeyConfig kc, Properties properties) {
        if (kc != null) {
            setProperty(kc.password(), properties, SslConfigs.SSL_KEY_PASSWORD_CONFIG);
        }
    }

    private static <T> void setProperty(Optional<T> property, Properties properties, String key) {
        setProperty(property, properties, key, Objects::toString);
    }

    private static <T> void setProperty(Optional<T> property, Properties properties, String key, Function<T, String> fn) {
        if (property.isPresent()) {
            properties.put(key, fn.apply(property.get()));
        }
    }

    private static String asString(List<InetSocketAddress> addresses) {
        return addresses.stream()
                .map(KafkaStreamsProducer::toHostPort)
                .collect(Collectors.joining(","));
    }

    private static String toHostPort(InetSocketAddress inetSocketAddress) {
        return inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort();
    }

    private static Properties getAdminClientConfig(Properties properties) {
        Properties adminClientConfig = new Properties(properties);
        // include TLS config name if it has been configured
        if (properties.containsKey(TLS_CONFIG_NAME_KEY)) {
            adminClientConfig.put(TLS_CONFIG_NAME_KEY, properties.get(TLS_CONFIG_NAME_KEY));
        }
        // include other AdminClientConfig(s) that have been configured
        for (final String knownAdminClientConfig : AdminClientConfig.configNames()) {
            // give preference to admin.<propname> first
            if (properties.containsKey(StreamsConfig.ADMIN_CLIENT_PREFIX + knownAdminClientConfig)) {
                adminClientConfig.put(knownAdminClientConfig,
                        properties.get(StreamsConfig.ADMIN_CLIENT_PREFIX + knownAdminClientConfig));
            } else if (properties.containsKey(knownAdminClientConfig)) {
                adminClientConfig.put(knownAdminClientConfig, properties.get(knownAdminClientConfig));
            }
        }
        return adminClientConfig;
    }

    private static final class DurationToSecondsFunction implements Function<Duration, String> {

        private static final DurationToSecondsFunction INSTANCE = new DurationToSecondsFunction();

        @Override
        public String apply(Duration d) {
            return String.valueOf(d.getSeconds());
        }
    }
}
