package com.holliverse.logserver.config;

import com.holliverse.logserver.config.properties.KafkaAppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaPropertiesConfig {

    /**
     * app.kafka 설정 바인딩 빈.
     */
    @Bean("kafkaAppProperties")
    @ConfigurationProperties(prefix = "app.kafka")
    public KafkaAppProperties kafkaAppProperties() {
        return new KafkaAppProperties();
    }
}
