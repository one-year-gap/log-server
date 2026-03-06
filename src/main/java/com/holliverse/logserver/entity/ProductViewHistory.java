package com.holliverse.logserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_view_history")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductViewHistory {

    @EmbeddedId
    private ProductViewHistoryId id;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "product_type", nullable = false, length = 50)
    private String productType;

    // List<String>을 JSON 문자열로 직렬화해 저장. DB DDL에서 jsonb 타입으로 선언됨.
    @Column(columnDefinition = "jsonb")
    private String tags;

    @Column(name = "viewed_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime viewedAt;

    @Column(name = "last_event_id", nullable = false)
    private Long lastEventId;
}
