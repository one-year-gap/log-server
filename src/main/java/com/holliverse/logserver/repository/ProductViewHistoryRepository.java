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
     * 복합 PK (member_id, product_id) 기준 UPSERT.
     * 동일 사용자가 동일 상품을 재조회하면 나머지 컬럼 전체를 최신값으로 덮어씀.
     * tags는 Java String → CAST AS jsonb 로 PostgreSQL 레벨에서 변환됨.
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
        @Param("memberId")    Long memberId,
        @Param("productId")   Long productId,
        @Param("productName") String productName,
        @Param("productType") String productType,
        @Param("tags")        String tags,
        @Param("viewedAt")    OffsetDateTime viewedAt,
        @Param("lastEventId") Long lastEventId
    );

    /**
     * 유저당 최신 N개(viewed_at DESC)를 초과하는 오래된 레코드 삭제.
     * 복합 PK 구조라 id 컬럼이 없으므로 product_id NOT IN 서브쿼리로 대상을 특정함.
     */
    @Modifying
    @Query(value = """
        DELETE FROM product_view_history
        WHERE member_id = :memberId
          AND product_id NOT IN (
              SELECT product_id
              FROM   product_view_history
              WHERE  member_id = :memberId
              ORDER  BY viewed_at DESC
              LIMIT  :maxCount
          )
        """, nativeQuery = true)
    void trimOldRecords(
        @Param("memberId") Long memberId,
        @Param("maxCount") int maxCount
    );
}
