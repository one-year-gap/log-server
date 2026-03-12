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

    // topics, groupId를 SpEL로 application.yaml의 app.kafka 값에서 주입
    // RecordFilterStrategy가 설정 레벨에서 click_product_detail만 통과시키므로
    // 이 메서드에 도달하는 메시지는 이미 필터링된 상태임
    @KafkaListener(
        topics = "#{@kafkaAppProperties.topics.clientEvents}",
        groupId = "#{@kafkaAppProperties.groups.speed}",
        containerFactory = "speedLayerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
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
            log.error("[SpeedLayer DLQ] topic={}, partition={}, offset={}, err={}",
                record.topic(), record.partition(), record.offset(), e.getMessage());
        }
    }
}
