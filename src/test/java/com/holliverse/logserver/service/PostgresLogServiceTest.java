package com.holliverse.logserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.dto.LogEvent;
import com.holliverse.logserver.repository.ProductViewHistoryRepository;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PostgresLogService 단위 테스트.
 * Repository는 Mock, ObjectMapper는 실제 인스턴스를 사용하여
 * JSON 직렬화 로직까지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PostgresLogServiceTest {

    @Mock
    private ProductViewHistoryRepository repository;

    private PostgresLogService service;

    @BeforeEach
    void setUp() {
        // ObjectMapper는 실제 인스턴스 — tags 직렬화 결과까지 검증
        service = new PostgresLogService(repository, new ObjectMapper());
    }

    // ----------------------------------------------------------------
    // Case 1: 정상 신규 삽입 — upsert() 호출 인자 전체 검증
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case 1 | 정상 신규 삽입 — upsert() 인자 정확히 전달")
    void case1_normalInsert_callsUpsertWithCorrectArgs() throws Exception {
        LogEvent event = buildEvent(
            1001L, 45L, 10L,
            "2026-03-02T16:30:00.000Z",
            List.of("영상OTT", "구독결제", "인기")
        );

        service.process(event);

        // upsert() 인자 캡처 후 검증
        ArgumentCaptor<String> tagsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<OffsetDateTime> viewedAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);

        verify(repository).upsert(
            eq(45L),                    // memberId
            eq(10L),                    // productId
            eq("5G 요금제"),            // productName
            eq("mobile"),               // productType
            tagsCaptor.capture(),       // tags JSON
            viewedAtCaptor.capture(),   // viewedAt
            eq(1001L)                   // lastEventId
        );

        // tags가 JSON 배열 문자열로 직렬화되었는지 확인
        assertThat(tagsCaptor.getValue()).isEqualTo("[\"영상OTT\",\"구독결제\",\"인기\"]");

        // timestamp가 OffsetDateTime으로 올바르게 파싱되었는지 확인
        assertThat(viewedAtCaptor.getValue())
            .isEqualTo(OffsetDateTime.parse("2026-03-02T16:30:00.000Z"));
    }


    // ----------------------------------------------------------------
    // Case 3: tags 빈 배열 → "[]" 로 직렬화
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case 3 | tags 빈 배열 — JSON 빈 배열 \"[]\" 로 저장")
    void case3_emptyTags_serializedAsEmptyJsonArray() throws Exception {
        LogEvent event = buildEvent(3001L, 45L, 30L,
            "2026-03-02T16:32:00.000Z", List.of());

        service.process(event);

        ArgumentCaptor<String> tagsCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(anyLong(), anyLong(), anyString(), anyString(),
            tagsCaptor.capture(), any(), anyLong());

        assertThat(tagsCaptor.getValue()).isEqualTo("[]");
    }

    // ----------------------------------------------------------------
    // Case 4: event_properties에 tags 키 자체가 없는 경우 → "[]"
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case 4 | tags 키 누락 — null 대신 빈 배열 \"[]\" 로 처리")
    void case4_tagsMissing_defaultsToEmptyJsonArray() throws Exception {
        LogEvent event = buildEvent(4001L, 45L, 40L,
            "2026-03-02T16:33:00.000Z", null);  // null → 키 미포함으로 설정

        service.process(event);

        ArgumentCaptor<String> tagsCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(anyLong(), anyLong(), anyString(), anyString(),
            tagsCaptor.capture(), any(), anyLong());

        assertThat(tagsCaptor.getValue()).isEqualTo("[]");
    }

    // ----------------------------------------------------------------
    // Case 5: product_id가 Integer(Jackson 기본 숫자 타입)로 역직렬화된 경우 → Long 변환
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case 5 | product_id Integer → Long 변환 — ClassCastException 없음")
    void case5_productIdAsInteger_convertedToLong() throws Exception {
        LogEvent event = new LogEvent();
        event.setEventId(5001L);
        event.setTimestamp("2026-03-02T16:34:00.000Z");
        event.setMemberId(45L);

        Map<String, Object> props = new HashMap<>();
        props.put("product_id", 50);  // Jackson이 역직렬화할 때 Integer로 오는 케이스
        props.put("product_name", "인터넷");
        props.put("product_type", "internet");
        props.put("tags", List.of());
        event.setEventProperties(props);

        service.process(event);

        verify(repository).upsert(eq(45L), eq(50L), anyString(), anyString(),
            anyString(), any(), anyLong());
    }

    // ----------------------------------------------------------------
    // Case 6: product_id null → IllegalArgumentException 발생, upsert 미호출
    // ----------------------------------------------------------------
    @Test
    @DisplayName("Case 6 | product_id null — IllegalArgumentException 발생, upsert 미호출")
    void case6_productIdNull_throwsIllegalArgumentException() {
        LogEvent event = new LogEvent();
        event.setEventId(6001L);
        event.setTimestamp("2026-03-02T16:35:00.000Z");
        event.setMemberId(45L);

        Map<String, Object> props = new HashMap<>();
        props.put("product_id", null);
        props.put("product_name", "오류상품");
        props.put("product_type", "mobile");
        props.put("tags", List.of());
        event.setEventProperties(props);

        assertThatThrownBy(() -> service.process(event))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("product_id 변환 실패");

        verify(repository, never()).upsert(anyLong(), anyLong(), anyString(), anyString(),
            anyString(), any(), anyLong());
    }

    // ----------------------------------------------------------------
    // 헬퍼 메서드
    // ----------------------------------------------------------------

    private LogEvent buildEvent(long eventId, long memberId, long productId,
                                String timestamp, List<String> tags) {
        LogEvent event = new LogEvent();
        event.setEventId(eventId);
        event.setTimestamp(timestamp);
        event.setEvent("click");
        event.setEventName("click_product_detail");
        event.setMemberId(memberId);

        Map<String, Object> props = new HashMap<>();
        props.put("product_id", productId);
        props.put("product_name", "5G 요금제");
        props.put("product_type", "mobile");
        if (tags != null) {
            props.put("tags", tags);
        }
        event.setEventProperties(props);
        return event;
    }
}
