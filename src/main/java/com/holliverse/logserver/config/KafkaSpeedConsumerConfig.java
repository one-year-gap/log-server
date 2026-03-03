package com.holliverse.logserver.config;

import com.holliverse.logserver.config.properties.KafkaAppProperties;
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

@Configuration
@EnableKafka
public class KafkaSpeedConsumerConfig {

    private final KafkaAppProperties kafkaAppProperties;

    public KafkaSpeedConsumerConfig(KafkaAppProperties kafkaAppProperties) {
        this.kafkaAppProperties = kafkaAppProperties;
    }

    /* consumer factory bean */
    // MAX_POLL_RECORDS_CONFIG, 1 (중요): 폴링할때 한건만 가져옴 
    // ENABLE_AUTO_COMMIT_CONFIG, false: 자동 커밋 비활성화 -> 수동 커밋 필요 -> AckMode.RECORD 사용으로 코드 로직이 성공후 커밋
    // AUTO_OFFSET_RESET_CONFIG, earliest: 컨슈머 그룹이 처음 생성되었을때, 토픽의 맨 처음 데이터부터 읽어옴 -> 데이터 유실 방지 

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

    /* container Factory bean -> 실제 kafka listener 돌아가는 엔진  */ 
    // AckMode.RECORD 사용으로 코드 로직이 성공후 커밋 -> 1건 처리하자마자 즉시 커밋 
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> speedLayerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(speedConsumerFactory());
        factory.getContainerProperties().setAckMode(
            ContainerProperties.AckMode.valueOf(kafkaAppProperties.getListener().getAckMode()));
        return factory;
    }
}
