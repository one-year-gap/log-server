package com.holliverse.logserver.repository;

import com.holliverse.logserver.entity.ProductViewHistory;
import com.holliverse.logserver.entity.ProductViewHistoryId;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductViewHistoryRepository
    extends JpaRepository<ProductViewHistory, ProductViewHistoryId> {

    /**
     * 최근 본 상품 upsert 쿼리.
     */
    @Modifying
    @Query(value = """
        INSERT INTO product_view_history
            (member_id, product_id, product_name, product_type, tags, viewed_at, last_event_id)
        VALUES
            (:memberId, :productId, :productName, :productType,
             CAST(:tags AS jsonb), :viewedAt, :lastEventId)
        ON CONFLICT (member_id, product_id)
        DO UPDATE SET
            product_name  = EXCLUDED.product_name,
            product_type  = EXCLUDED.product_type,
            tags          = EXCLUDED.tags,
            viewed_at     = EXCLUDED.viewed_at,
            last_event_id = EXCLUDED.last_event_id
        """, nativeQuery = true)
    void upsert(
        @Param("memberId") Long memberId,
        @Param("productId") Long productId,
        @Param("productName") String productName,
        @Param("productType") String productType,
        @Param("tags") String tags,
        @Param("viewedAt") OffsetDateTime viewedAt,
        @Param("lastEventId") Long lastEventId
    );

    /**
     * 유저별 오래된 기록 정리 쿼리.
     */
    @Modifying
    @Query(value = """
        DELETE FROM product_view_history
        WHERE member_id = :memberId
          AND product_id NOT IN (
              SELECT product_id
              FROM product_view_history
              WHERE member_id = :memberId
              ORDER BY viewed_at DESC
              LIMIT :maxCount
          )
        """, nativeQuery = true)
    void trimOldRecords(
        @Param("memberId") Long memberId,
        @Param("maxCount") int maxCount
    );
}
