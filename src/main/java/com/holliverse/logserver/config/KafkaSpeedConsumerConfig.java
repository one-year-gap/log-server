package com.holliverse.logserver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.config.properties.KafkaAppProperties;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@EnableKafka
public class KafkaSpeedConsumerConfig {

    private static final String FILTER_EVENT_NAME = "click_product_detail";

    private final KafkaAppProperties kafkaAppProperties;
    private final ObjectMapper objectMapper;

    public KafkaSpeedConsumerConfig(KafkaAppProperties kafkaAppProperties, ObjectMapper objectMapper) {
        this.kafkaAppProperties = kafkaAppProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * speed consumer factory 빈.
     */
    @Bean
    public ConsumerFactory<String, String> speedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        // 브로커 주소
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAppProperties.getBootstrapServers());
        // 그룹 아이디
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaAppProperties.getGroups().getSpeed());
        // 키 역직렬화
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 값 역직렬화
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 자동 커밋 비활성
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // poll 크기
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafkaAppProperties.getListener().getMaxPollRecords());
        // 초기 오프셋 정책
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
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

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * speed listener container 빈.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> speedLayerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        // consumer factory 연결
        factory.setConsumerFactory(speedConsumerFactory());
        // ack 모드
        factory.getContainerProperties().setAckMode(
            kafkaAppProperties.getListener().getAckMode());
        // 자동 시작 여부
        factory.setAutoStartup(kafkaAppProperties.getListener().isAutoStartup());

        // 이벤트 필터
        factory.setRecordFilterStrategy(record -> {
            try {
                JsonNode node = objectMapper.readTree(record.value());
                return !FILTER_EVENT_NAME.equals(node.path("event_name").asText(""));
            } catch (Exception e) {
                // 파싱 실패 폐기
                log.warn("레코드 필터링 중 value 매칭 실패로 폐기합니다. value={}", record.value(), e);
                return true;
            }
        });

        return factory;
    }
}
