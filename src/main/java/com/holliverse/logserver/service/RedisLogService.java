package com.holliverse.logserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holliverse.logserver.dto.LogEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLogService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // recent_views 최대 보관 개수
    private static final int MAX_RECENT_VIEWS = 30;

    /**
     * click_product_detail 이벤트 1건에 대해 2개 ZSET 동시 업데이트
     *  1) user:{member_id}:recent_views  — 최근 본 상품 (최신순 정렬, 중복 제거)
     *  2) user:{member_id}:tag_scores    — 태그 선호도 누적 점수
     */
    public void process(LogEvent event) throws JsonProcessingException {
        String memberId = event.getMemberProperties().getMemberId();
        Map<String, Object> props = event.getEventProperties();

        updateRecentViews(memberId, props, event.getTimestamp());
        updateTagScores(memberId, props);
    }

    /**
     * ZSET: user:{member_id}:recent_views
     * Score = 이벤트 타임스탬프 (epoch ms) → 높을수록 최신
     * Value = 프론트엔드 렌더링용 최소 JSON string
     * 최대 30개 유지: ZREMRANGEBYRANK로 오래된 항목 자동 삭제
     */
    private void updateRecentViews(String memberId, Map<String, Object> props, String timestamp)
        throws JsonProcessingException {

        String key = "user:" + memberId + ":recent_views";

        // ISO 8601 → epoch ms (ZSET score는 double)
        double score = (double) Instant.parse(timestamp).toEpochMilli();

        // 프론트가 화면을 그릴 최소 데이터만 포함
        Map<String, Object> viewData = new LinkedHashMap<>();
        viewData.put("product_id", props.get("product_id"));
        viewData.put("target_url", props.getOrDefault("page_url", ""));
        String valueJson = objectMapper.writeValueAsString(viewData);

        // ZADD: 이미 같은 value가 있으면 score(timestamp)만 갱신 → 멱등성 보장
        redisTemplate.opsForZSet().add(key, valueJson, score);

        // 최대 30개 초과분 삭제: rank 0(가장 오래된) ~ -(MAX+1) 범위 제거
        // ex) 31개가 되는 순간 rank 0 항목 1개가 삭제되어 항상 30개 유지
        redisTemplate.opsForZSet().removeRange(key, 0, -(MAX_RECENT_VIEWS + 1));
    }

    /**
     * ZSET: user:{member_id}:tag_scores
     * ZINCRBY: 태그마다 +1점씩 누적
     * 같은 태그가 중복 도착해도 score 합산으로 자연스럽게 선호도 반영
     */
    @SuppressWarnings("unchecked")
    private void updateTagScores(String memberId, Map<String, Object> props) {
        String key = "user:" + memberId + ":tag_scores";
        List<String> tags = (List<String>) props.getOrDefault("tags", Collections.emptyList());
        for (String tag : tags) {
            redisTemplate.opsForZSet().incrementScore(key, tag, 1.0);
        }
    }
}
