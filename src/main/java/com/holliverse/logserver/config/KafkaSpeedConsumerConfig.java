package com.holliverse.logserver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.config.properties.KafkaAppProperties;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

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

    /* consumer factory bean */
    // MAX_POLL_RECORDS_CONFIG, 1 (중요): 폴링할때 한건만 가져옴
    // ENABLE_AUTO_COMMIT_CONFIG, false: 자동 커밋 비활성화 → AckMode.RECORD로 처리 성공 후 커밋
    // AUTO_OFFSET_RESET_CONFIG, earliest: 컨슈머 그룹 최초 생성 시 맨 처음 데이터부터 읽어 유실 방지
    @Bean
    public ConsumerFactory<String, String> speedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAppProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaAppProperties.getGroups().getSpeed());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafkaAppProperties.getListener().getMaxPollRecords());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /* container factory bean — 실제 Kafka Listener 엔진 */
    // RecordFilterStrategy: event_name != "click_product_detail" 이면 메시지를 @KafkaListener 전에 폐기
    //   → true 반환 = 폐기(discard), false 반환 = 리스너로 전달
    // AckMode.RECORD: 1건 처리 완료 즉시 커밋 → 재처리 범위를 최소화
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> speedLayerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(speedConsumerFactory());
        factory.getContainerProperties().setAckMode(
            ContainerProperties.AckMode.valueOf(kafkaAppProperties.getListener().getAckMode()));

        factory.setRecordFilterStrategy(record -> {
            try {
                JsonNode node = objectMapper.readTree(record.value());
                return !FILTER_EVENT_NAME.equals(node.path("event_name").asText(""));
            } catch (Exception e) {
                // 파싱 불가 = 깨진 JSON → 폐기 (Consumer의 DLQ와 역할 분리)
                log.warn("레코드 필터링 중 value 매칭 실패로 폐기합니다. value={}", record.value(), e);
                return true;
            }
        });

        return factory;
    }
}
