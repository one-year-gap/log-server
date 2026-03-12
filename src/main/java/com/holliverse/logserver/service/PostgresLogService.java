package com.holliverse.logserver.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.dto.LogEvent;
import com.holliverse.logserver.repository.ProductViewHistoryRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
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
     * 해당 유저의 레코드가 MAX_RECENT_VIEWS 를 초과하면 오래된 항목 자동 삭제.
     */
    @Transactional
    public void process(LogEvent event) throws JsonProcessingException {
        Long memberId = event.getMemberId();
        ClickProductDetailProperties props = objectMapper.convertValue(
            event.getEventProperties(),
            ClickProductDetailProperties.class
        );

        Long productId = props.getProductId();
        if (productId == null) {
            throw new IllegalArgumentException("product_id 변환 실패: null");
        }
        String productName = props.getProductName();
        String productType = props.getProductType();
        List<String> tagList = props.getTags() != null ? props.getTags() : Collections.emptyList();
        String tags = objectMapper.writeValueAsString(tagList);

        OffsetDateTime viewedAt = OffsetDateTime.parse(event.getTimestamp());
        Long lastEventId = event.getEventId();

        repository.upsert(memberId, productId, productName, productType, tags, viewedAt, lastEventId);
        repository.trimOldRecords(memberId, MAX_RECENT_VIEWS);
        log.debug("[PostgresLog] UPSERT 완료 memberId={} productId={}", memberId, productId);
    }

    /**
     * click_product_detail 이벤트의 event_properties 전용 DTO.
     * ObjectMapper.convertValue()로 변환하여 Map 직접 캐스팅 및 ClassCastException을 방지.
     * JSON 키(snake_case)는 @JsonProperty로 Java CamelCase 필드에 매핑.
     */
    @Getter
    @Setter
    private static class ClickProductDetailProperties {

        @JsonProperty("product_id")
        private Long productId;

        @JsonProperty("product_name")
        private String productName;

        @JsonProperty("product_type")
        private String productType;

        private List<String> tags;
    }
}
