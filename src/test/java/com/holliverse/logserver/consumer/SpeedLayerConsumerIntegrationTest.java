package com.holliverse.logserver.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.holliverse.logserver.entity.ProductViewHistory;
import com.holliverse.logserver.entity.ProductViewHistoryId;
import com.holliverse.logserver.repository.ProductViewHistoryRepository;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kafka → SpeedLayerConsumer → PostgreSQL 전체 파이프라인 통합 테스트.
 *
 * 인프라:
 *  - EmbeddedKafka  : 인메모리 Kafka 브로커 (포트 랜덤)
 *  - PostgreSQLContainer : TestContainers PostgreSQL 16
 *
 * 부하 근거 (Case 6):
 *  - 일 사용자 3만 명 × 10회/일 = 30만 건/일
 *  - 30만 / 86,400초 ≈ 3.47 TPS (평균)
 *  - 피크타임(전체의 30% 집중, 4시간) ≈ 6.25 TPS
 *  → MAX_POLL_RECORDS=1, ACK_MODE=RECORD 설정으로도 충분히 처리 가능함을 검증
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(
    partitions = 1,
    topics = {"client-event-logs", "error-logs"},
    bootstrapServersProperty = "app.kafka.bootstrap-servers"
)
@Testcontainers
@DirtiesContext
class SpeedLayerConsumerIntegrationTest {

    // ----------------------------------------------------------------
    // TestContainers PostgreSQL (클래스 전체 공유, 최초 1회 기동)
    // ----------------------------------------------------------------
    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("logdb")
            .withUsername("loguser")
            .withPassword("logpass");

    // Hibernate ddl-auto=create → 테이블 자동 생성 (테스트 전용)
    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    // @EmbeddedKafka 가 테스트 컨텍스트에서만 빈을 등록하므로 IDE가 미인식 → 런타임에는 정상 주입됨
    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ProductViewHistoryRepository repository;

    // 테스트에서 client-event-logs 토픽으로 메시지를 발행할 프로듀서
    private KafkaTemplate<String, String> testProducer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        testProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        repository.deleteAll();
    }

    // ================================================================
    // Case 1: 신규 삽입 — DB에 1건 적재 확인
    // ================================================================
    @Test
    @DisplayName("Case 1 | 신규 삽입 — product_view_history에 1건 적재")
    void case1_normalInsert_savedToDatabase() {
        String payload = buildPayload(1001L, 45L, 10L,
            "2026-03-02T16:30:00.000Z", "5G 요금제", "mobile", "[\"영상OTT\",\"인기\"]");

        testProducer.send("client-event-logs", payload);

        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ProductViewHistory row = repository
                    .findById(new ProductViewHistoryId(45L, 10L))
                    .orElseThrow();

                assertThat(row.getProductName()).isEqualTo("5G 요금제");
                assertThat(row.getProductType()).isEqualTo("mobile");
                assertThat(row.getTags()).contains("영상OTT");
                assertThat(row.getLastEventId()).isEqualTo(1001L);
            });
    }

    // ================================================================
    // Case 2: UPSERT — 동일 (member_id, product_id) 재조회 시 viewed_at 갱신
    // ================================================================
    @Test
    @DisplayName("Case 2 | UPSERT — 동일 상품 재조회 시 viewed_at·last_event_id 갱신")
    void case2_upsert_updatesViewedAtOnDuplicate() {
        String first = buildPayload(2001L, 45L, 20L,
            "2026-03-02T09:00:00.000Z", "LTE 요금제", "mobile", "[]");
        String second = buildPayload(2002L, 45L, 20L,
            "2026-03-02T18:00:00.000Z", "LTE 요금제", "mobile", "[]");

        testProducer.send("client-event-logs", first);
        await().atMost(15, TimeUnit.SECONDS)
            .until(() -> repository.findById(new ProductViewHistoryId(45L, 20L)).isPresent());

        testProducer.send("client-event-logs", second);

        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ProductViewHistory row = repository
                    .findById(new ProductViewHistoryId(45L, 20L))
                    .orElseThrow();

                // 최신 이벤트로 덮어써진 상태
                assertThat(row.getLastEventId()).isEqualTo(2002L);
                assertThat(row.getViewedAt()).isEqualTo(java.time.OffsetDateTime.parse("2026-03-02T18:00:00Z"));
            });

        // 레코드는 여전히 1건
        assertThat(repository.count()).isEqualTo(1L);
    }

    // ================================================================
    // Case 3: TRIM — 유저당 31번째 상품 추가 시 가장 오래된 1개 삭제
    // ================================================================
    @Test
    @DisplayName("Case 3 | TRIM — 31개 발행 후 유저당 최신 30개만 유지")
    void case3_trim_keepsMax30RecordsPerMember() throws InterruptedException {
        // 30개 먼저 삽입 (product_id 1~30, 시간 순차 증가)
        for (int i = 1; i <= 30; i++) {
            String ts = "2026-03-02T%02d:00:00.000Z".formatted(i % 24);
            testProducer.send("client-event-logs",
                buildPayload(3000L + i, 99L, (long) i, ts,
                    "상품" + i, "mobile", "[]"));
        }

        // 30개 적재 완료 대기
        await().atMost(60, TimeUnit.SECONDS)
            .until(() -> repository.count() == 30L);

        // 31번째 상품 추가 → TRIM 발동
        testProducer.send("client-event-logs",
            buildPayload(3031L, 99L, 31L,
                "2026-03-02T23:59:00.000Z", "신상품31", "mobile", "[]"));

        // TRIM 후 30개만 남아야 함
        await().atMost(15, TimeUnit.SECONDS)
            .until(() -> repository.count() == 30L);

        assertThat(repository.count()).isEqualTo(30L);
    }

    // ================================================================
    // Case 4: event_name 필터링 — click_product_detail 외 이벤트 무시
    // ================================================================
    @Test
    @DisplayName("Case 4 | event_name 필터링 — page_view 이벤트는 DB 미적재")
    void case4_eventNameFilter_nonTargetEventIgnored() throws InterruptedException {
        String wrongEvent = """
            {
              "event_id": 4001,
              "timestamp": "2026-03-02T16:30:00.000Z",
              "event": "page_view",
              "event_name": "page_view",
              "member_id": 45,
              "event_properties": {
                "product_id": 40,
                "product_name": "무시상품",
                "product_type": "mobile",
                "tags": []
              }
            }
            """;

        testProducer.send("client-event-logs", wrongEvent);

        // RecordFilterStrategy에서 폐기되므로 DB는 빈 상태 유지 (500ms 후 확인)
        Thread.sleep(2000);
        assertThat(repository.count()).isEqualTo(0L);
    }

    // ================================================================
    // Case 5: event_id 타입 오류 (String UUID) → DLQ(error-logs) 전송, DB 미적재
    // ================================================================
    @Test
    @DisplayName("Case 5 | event_id 타입 오류 — 역직렬화 실패 시 error-logs DLQ 전송")
    void case5_invalidEventIdType_sentToDlq() {
        String brokenPayload = """
            {
              "event_id": "uuid-1234-5678",
              "timestamp": "2026-03-02T17:10:00.000Z",
              "event": "click",
              "event_name": "click_product_detail",
              "member_id": 45,
              "event_properties": {
                "product_id": 47,
                "product_name": "타입오류",
                "product_type": "mobile",
                "tags": []
              }
            }
            """;

        // DLQ(error-logs) 전용 테스트 컨슈머 구성
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
            "dlq-test-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Consumer<String, String> dlqConsumer =
            new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        dlqConsumer.subscribe(Collections.singletonList("error-logs"));

        testProducer.send("client-event-logs", brokenPayload);

        // error-logs 토픽에 원본 메시지가 들어왔는지 확인
        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records =
                    dlqConsumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isGreaterThan(0);
                assertThat(records.iterator().next().value()).contains("uuid-1234-5678");
            });

        dlqConsumer.close();

        // DB에는 적재되지 않음
        assertThat(repository.count()).isEqualTo(0L);
    }

    // ================================================================
    // Case 6: 부하 시나리오 — 50건 처리 시간으로 TPS 검증
    //
    // 목표: 일 3만명 × 10회 = 30만 건/일 ≈ 3.47 TPS (평균), 6.25 TPS (피크)
    // 검증: 50건을 처리하는 실제 시간으로 가용 TPS 측정
    // ================================================================
    @Test
    @DisplayName("Case 6 | 부하 시나리오 — 50건 처리 TPS ≥ 10 (운영 요구 6.25 TPS의 1.6배 여유)")
    void case6_throughput_50MessagesProcessedWithSufficientTps() {
        int messageCount = 50;

        long startMs = System.currentTimeMillis();

        for (int i = 1; i <= messageCount; i++) {
            testProducer.send("client-event-logs",
                buildPayload(6000L + i, 77000L + i, (long) i,
                    "2026-03-02T16:30:00.000Z", "상품" + i, "mobile", "[]"));
        }

        await().atMost(60, TimeUnit.SECONDS)
            .until(() -> repository.count() == (long) messageCount);

        long elapsedMs = System.currentTimeMillis() - startMs;
        double tps = messageCount / (elapsedMs / 1000.0);

        System.out.printf(
            "[부하 테스트] %d건 처리 완료 | 소요시간: %dms | 처리량: %.2f TPS%n",
            messageCount, elapsedMs, tps);
        System.out.printf(
            "[운영 요구사항] 평균 3.47 TPS / 피크 6.25 TPS → 현재 %.2f TPS (%.1f배 여유)%n",
            tps, tps / 6.25);

        // MAX_POLL_RECORDS=1 + ACK_MODE=RECORD 설정으로도 피크 TPS의 1.6배 이상 처리 가능해야 함
        assertThat(tps).isGreaterThan(10.0);
    }

    // ----------------------------------------------------------------
    // 헬퍼 — Kafka 발행용 JSON 페이로드 생성
    // ----------------------------------------------------------------
    private String buildPayload(long eventId, long memberId, long productId,
                                String timestamp, String productName,
                                String productType, String tagsJson) {
        return """
            {
              "event_id": %d,
              "timestamp": "%s",
              "event": "click",
              "event_name": "click_product_detail",
              "member_id": %d,
              "event_properties": {
                "product_id": %d,
                "product_name": "%s",
                "product_type": "%s",
                "tags": %s
              }
            }
            """.formatted(eventId, timestamp, memberId, productId,
                productName, productType, tagsJson);
    }
}
