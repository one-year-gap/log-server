package com.holliverse.logserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.dto.LogEvent;
import com.holliverse.logserver.repository.ProductViewHistoryRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostgresLogService {

    private static final int MAX_RECENT_VIEWS = 30;

    private final ProductViewHistoryRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * click_product_detail 이벤트 1건을 product_view_history 테이블에 UPSERT 후
     *  해당 유저의 레코드가 MAX_RECENT_VIEWS 를 초과하면 오래된 항목 자동 삭제.
     */
    @Transactional
    public void process(LogEvent event) throws JsonProcessingException {
        Long memberId = event.getMemberId();
        Map<String, Object> props = event.getEventProperties();

        Long productId   = toLong(props.get("product_id"));
        String productName = (String) props.get("product_name");
        String productType = (String) props.get("product_type");

        // List<String> → JSON 배열 문자열 (CAST AS jsonb 로 PostgreSQL에 전달)
        @SuppressWarnings("unchecked")
        List<String> tagList = (List<String>) props.getOrDefault("tags", Collections.emptyList());
        String tags = objectMapper.writeValueAsString(tagList);

        // ISO-8601 타임스탬프 → OffsetDateTime (TIMESTAMPTZ 바인딩)
        OffsetDateTime viewedAt = OffsetDateTime.parse(event.getTimestamp());

        Long lastEventId = event.getEventId();

        repository.upsert(memberId, productId, productName, productType, tags, viewedAt, lastEventId);
        repository.trimOldRecords(memberId, MAX_RECENT_VIEWS);

        log.debug("[PostgresLog] UPSERT 완료 memberId={} productId={}", memberId, productId);
    }

    private Long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalArgumentException("product_id 변환 실패: " + value);
    }
}
