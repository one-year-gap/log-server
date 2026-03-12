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
     * 클릭 로그 DB 반영 메서드.
     */
    @Transactional
    public void process(LogEvent event) throws JsonProcessingException {
        // 사용자 아이디
        Long memberId = event.getMemberId();
        // 속성 변환
        ClickProductDetailProperties props = objectMapper.convertValue(
            event.getEventProperties(),
            ClickProductDetailProperties.class
        );

        // 상품 아이디
        Long productId = props.getProductId();
        if (productId == null) {
            throw new IllegalArgumentException("product_id 변환 실패: null");
        }
        // 상품 이름
        String productName = props.getProductName();
        // 상품 타입
        String productType = props.getProductType();
        // 태그 목록
        List<String> tagList = props.getTags() != null ? props.getTags() : Collections.emptyList();
        // 태그 직렬화
        String tags = objectMapper.writeValueAsString(tagList);

        // 조회 시각
        OffsetDateTime viewedAt = OffsetDateTime.parse(event.getTimestamp());
        // 마지막 이벤트 아이디
        Long lastEventId = event.getEventId();

        // 최근 본 상품 upsert
        repository.upsert(memberId, productId, productName, productType, tags, viewedAt, lastEventId);
        // 오래된 기록 정리
        repository.trimOldRecords(memberId, MAX_RECENT_VIEWS);

        // 처리 로그
        log.debug("[PostgresLog] UPSERT 완료 memberId={} productId={}", memberId, productId);
    }

    /**
     * 클릭 상세 속성 DTO.
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
