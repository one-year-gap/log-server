package com.holliverse.logserver.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.kafka.listener.ContainerProperties;

@Getter
@Setter
public class KafkaAppProperties {

    private String bootstrapServers = "localhost:9092";
    private Topics topics = new Topics();
    private Groups groups = new Groups();
    private Listener listener = new Listener();
    private Producer producer = new Producer();
    private Security security = new Security();

    @Getter
    @Setter
    public static class Topics {
        private String clientEvents = "client-event-logs";
        private String error = "error-logs";
    }

    @Getter
    @Setter
    public static class Groups {
        private String speed = "speed-layer-group";
    }

    @Getter
    @Setter
    public static class Listener {
        private int maxPollRecords = 1;
        private ContainerProperties.AckMode ackMode = ContainerProperties.AckMode.RECORD;
        private boolean autoStartup = true;
    }

    @Getter
    @Setter
    public static class Producer {
        private String dlqAcks = "all";
        private int dlqRetries = 3;
    }

    @Getter
    @Setter
    public static class Security {
        private String protocol = "PLAINTEXT";
        private String saslMechanism;
        private String saslJaasConfig;
        private String saslCallbackHandlerClass;
    }
}
