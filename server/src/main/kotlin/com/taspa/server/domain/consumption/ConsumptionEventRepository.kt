package com.taspa.server.domain.consumption

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ConsumptionEventRepository : JpaRepository<ConsumptionEvent, UUID> {
    /**
     * 멱등 upsert 키 조회 — (org_id, source, external_id) UNIQUE(org 범위). 생산자 재전송을 no-op/갱신으로
     * 흡수한다. ★org 스코프라 다른 조직이 같은 external_id 를 써도 충돌하지 않고, 교차-테넌트 하이재킹도
     * lookup 자체가 org 범위라 원천 차단된다(별도 사후 org 검사 불필요).
     */
    fun findByOrgIdAndSourceAndExternalId(
        orgId: UUID,
        source: String,
        externalId: String,
    ): ConsumptionEvent?

    /**
     * 멱등키 배치 조회 — ingest 가 이벤트당 SELECT(N+1) 대신 source 별로 묶어 한 번에 기존 행을 preload 한다.
     * org 스코프는 단건 조회와 동일하게 유지된다(교차 테넌트 행은 애초에 결과에 없다).
     */
    fun findByOrgIdAndSourceAndExternalIdIn(
        orgId: UUID,
        source: String,
        externalIds: Collection<String>,
    ): List<ConsumptionEvent>

    /**
     * 집계: org × date × meal_window(CONFIRMED 만). VOIDED 는 제외한다.
     * date 버킷은 org-로컬 타임존(:orgTz)으로 앵커링한다 — occurred_at 은 UTC wall-clock(tz-naive)이라
     * UTC 로 해석한 뒤 org 존으로 변환해 절단해야 로컬 달력과 어긋나지 않는다(예: KST 아침이 전날로 오귀속되는 것 방지).
     * 반환 행: [bucket_date(java.sql.Date), meal_window(String), event_count(Long), total_quantity(Long)].
     * ★org 격리 — orgId 필수. 개별 이벤트·user_sub 는 반환하지 않는다(집계만 노출). :limit 로 결과 행을 상한한다.
     */
    @Query(
        // GROUP BY/ORDER BY 는 서수(1,2)로 SELECT 표현식을 참조한다 — tz 파라미터를 표현식마다 반복하면 Postgres
        // 가 각 바인드를 별개로 보아 SELECT 표현식과 GROUP BY 표현식을 동일 취급하지 못하기 때문(파라미터 1회 등장).
        value = """
            SELECT CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE :orgTz AS date) AS bucket_date,
                   meal_window,
                   COUNT(*) AS event_count,
                   COALESCE(SUM(quantity), 0) AS total_quantity
            FROM consumption_events
            WHERE org_id = :orgId AND status = 'CONFIRMED'
              AND occurred_at >= :from AND occurred_at < :to
            GROUP BY 1, 2
            ORDER BY 1, 2
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun aggregateByDateWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("orgTz") orgTz: String,
        @Param("limit") limit: Int,
    ): List<Array<Any>>

    /**
     * 집계: org × date × meal_window × menu_ref(CONFIRMED 만). menu_ref 미지정 행은 빈 문자열로 그룹핑된다.
     * date 버킷은 org-로컬 타임존(:orgTz)으로 앵커링한다(위 참조). :limit 로 그룹 수를 상한해 고카디널리티
     * menu_ref 로 인한 결과 폭발을 막는다.
     * 반환 행: [bucket_date, meal_window, menu_ref(String), event_count(Long), total_quantity(Long)].
     */
    @Query(
        // GROUP BY/ORDER BY 는 서수(1,2,3)로 SELECT 표현식을 참조한다(위 aggregateByDateWindow 주석 참조).
        value = """
            SELECT CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE :orgTz AS date) AS bucket_date,
                   meal_window,
                   COALESCE(menu_ref, '') AS menu_ref,
                   COUNT(*) AS event_count,
                   COALESCE(SUM(quantity), 0) AS total_quantity
            FROM consumption_events
            WHERE org_id = :orgId AND status = 'CONFIRMED'
              AND occurred_at >= :from AND occurred_at < :to
            GROUP BY 1, 2, 3
            ORDER BY 1, 2, 3
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun aggregateByDateWindowMenu(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("orgTz") orgTz: String,
        @Param("limit") limit: Int,
    ): List<Array<Any>>

    /**
     * 집계: org × date × site × meal_window(CONFIRMED 만) — 식수예측(P0) 입력 전용. site_id 미지정 행은
     * NULL 그룹으로 반환된다(호출부가 org 전체 롤업에 합산). date 버킷은 org-로컬 타임존 앵커링(위 참조).
     * 반환 행: [bucket_date(java.sql.Date), site_id(UUID?), meal_window(String), total_quantity(Long)].
     * ★org 격리 — orgId 필수. 집계 카운트만 반환한다(개별 이벤트·user_sub 미노출). :limit 로 행 상한.
     */
    @Query(
        // GROUP BY/ORDER BY 서수 참조 — tz 파라미터 1회 등장 원칙(위 aggregateByDateWindow 주석 참조).
        value = """
            SELECT CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE :orgTz AS date) AS bucket_date,
                   site_id,
                   meal_window,
                   COALESCE(SUM(quantity), 0) AS total_quantity
            FROM consumption_events
            WHERE org_id = :orgId AND status = 'CONFIRMED'
              AND occurred_at >= :from AND occurred_at < :to
            GROUP BY 1, 2, 3
            ORDER BY 1, 2, 3
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun aggregateByDateSiteWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("orgTz") orgTz: String,
        @Param("limit") limit: Int,
    ): List<Array<Any?>>

    /**
     * 집계: merchant × date × meal_window(CONFIRMED 만) — 가맹 그레인 식수예측 입력 전용.
     *
     * org 집계와 축이 다르다: 한 매장은 여러 조직 손님을 받으므로 **org 를 넘어 합산**한다(그게 매장이
     * 준비해야 할 실제 인분 수다). 테넌시 앵커는 org_id 가 아니라 merchant_id 이며, 이 값은 요청 경로의
     * 가맹점에 대한 활성 멤버십을 확인한 뒤에만 전달된다(정책 엔진 판정 — merchant TRN).
     *
     * date 버킷은 **merchant 타임존**(:merchantTz)으로 앵커링한다 — 조직 타임존을 빌려 쓰면 어느 조직
     * 기준인지 정할 수 없다(V29 주석). occurred_at 은 UTC wall-clock(tz-naive)이라 UTC 로 해석한 뒤
     * 매장 존으로 변환해 절단한다.
     *
     * 반환 행: [bucket_date(java.sql.Date), meal_window(String), total_quantity(Long)].
     * ★집계 수량만 반환한다 — user_sub·개별 이벤트는 나가지 않는다(손님 개인정보를 가맹점에 노출하지 않음).
     */
    @Query(
        // GROUP BY/ORDER BY 서수 참조 — tz 파라미터 1회 등장 원칙(위 aggregateByDateWindow 주석 참조).
        value = """
            SELECT CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE :merchantTz AS date) AS bucket_date,
                   meal_window,
                   COALESCE(SUM(quantity), 0) AS total_quantity
            FROM consumption_events
            WHERE merchant_id = :merchantId AND status = 'CONFIRMED'
              AND occurred_at >= :from AND occurred_at < :to
            GROUP BY 1, 2
            ORDER BY 1, 2
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun aggregateByMerchantDateWindow(
        @Param("merchantId") merchantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("merchantTz") merchantTz: String,
        @Param("limit") limit: Int,
    ): List<Array<Any?>>

    /**
     * 대사용 CONFIRMED 건수 — 청구·원장과 **같은 창**([from, to))으로 센다.
     *
     * 수량(quantity)이 아니라 **건수**를 세는 이유: 장부의 승인 건수와 맞대는 축이고, 결제 한 건이
     * 소비 이벤트 한 건에 1:1 대응한다는 것이 그 seam 의 계약이다. 수량은 결제 외 생산자가 여러 인분을
     * 한 건으로 올릴 수 있어 이 대사의 축이 아니다.
     */
    @Query(
        """
        SELECT COUNT(e) FROM ConsumptionEvent e
        WHERE e.orgId = :orgId AND e.status = 'CONFIRMED'
          AND e.occurredAt >= :from AND e.occurredAt < :to
        """,
    )
    fun countConfirmedInWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: java.time.Instant,
        @Param("to") to: java.time.Instant,
    ): Long
}
