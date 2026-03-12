package com.holliverse.logserver.config;

import com.holliverse.logserver.config.properties.KafkaAppProperties;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.util.StringUtils;

@Configuration
public class KafkaDlqProducerConfig {

    private final KafkaAppProperties kafkaAppProperties;

    public KafkaDlqProducerConfig(KafkaAppProperties kafkaAppProperties) {
        this.kafkaAppProperties = kafkaAppProperties;
    }

    @Bean
    public ProducerFactory<String, String> dlqProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        // 브로커 주소
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAppProperties.getBootstrapServers());
        // 키 직렬화
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 값 직렬화
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 보안 프로토콜
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafkaAppProperties.getSecurity().getProtocol());

        if (StringUtils.hasText(kafkaAppProperties.getSecurity().getSaslMechanism())) {
            // SASL 메커니즘
            props.put(SaslConfigs.SASL_MECHANISM, kafkaAppProperties.getSecurity().getSaslMechanism());
        }
        if (StringUtils.hasText(kafkaAppProperties.getSecurity().getSaslJaasConfig())) {
            // JAAS 설정
            props.put(SaslConfigs.SASL_JAAS_CONFIG, kafkaAppProperties.getSecurity().getSaslJaasConfig());
        }
        if (StringUtils.hasText(kafkaAppProperties.getSecurity().getSaslCallbackHandlerClass())) {
            // 콜백 핸들러
            props.put(
                SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                kafkaAppProperties.getSecurity().getSaslCallbackHandlerClass()
            );
        }

        // DLQ ack 설정
        props.put(ProducerConfig.ACKS_CONFIG, kafkaAppProperties.getProducer().getDlqAcks());
        // DLQ retry 설정
        props.put(ProducerConfig.RETRIES_CONFIG, kafkaAppProperties.getProducer().getDlqRetries());

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dlqProducerFactory());
    }
}
