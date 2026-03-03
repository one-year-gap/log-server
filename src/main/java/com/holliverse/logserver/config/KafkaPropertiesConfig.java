package com.holliverse.logserver.config;

import com.holliverse.logserver.config.properties.KafkaAppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaPropertiesConfig {

    /**
     * app.kafka.* 설정을 KafkaAppProperties에 바인딩하는 전용 Bean.
     * Bean 이름을 명시적으로 'kafkaAppProperties'로 고정해서
     * SpEL(@kafkaAppProperties)에서 안정적으로 참조할 수 있게 한다.
     */
    @Bean("kafkaAppProperties")
    @ConfigurationProperties(prefix = "app.kafka")
    public KafkaAppProperties kafkaAppProperties() {
        return new KafkaAppProperties();
    }
}

