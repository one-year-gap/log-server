package com.holliverse.logserver.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.config.properties.KafkaAppProperties;
import com.holliverse.logserver.dto.LogEvent;
import com.holliverse.logserver.service.PostgresLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpeedLayerConsumer {

    private final ObjectMapper objectMapper;
    private final PostgresLogService postgresLogService;
    private final KafkaTemplate<String, String> dlqKafkaTemplate;
    private final KafkaAppProperties kafkaAppProperties;

    /**
     * 클릭 로그 소비 메서드.
     */
    @KafkaListener(
        topics = "#{@kafkaAppProperties.topics.clientEvents}",
        groupId = "#{@kafkaAppProperties.groups.speed}",
        containerFactory = "speedLayerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            // 원본 로그 역직렬화
            LogEvent event = objectMapper.readValue(record.value(), LogEvent.class);
            postgresLogService.process(event);
        } catch (Exception e) {
            // 역직렬화 or PostgreSQL 처리 실패 → DLQ 토픽으로 원본 페이로드 전송
            // 예외를 re-throw하지 않아 다음 메시지 처리가 중단되지 않음 (무한 루프 방지)
            dlqKafkaTemplate.send(
                kafkaAppProperties.getTopics().getError(),
                record.key(),
                record.value()
            );
            // 에러 로그
            log.error("[SpeedLayer DLQ] topic={}, partition={}, offset={}, err={}",
                record.topic(), record.partition(), record.offset(), e.getMessage());
        }
    }
}
