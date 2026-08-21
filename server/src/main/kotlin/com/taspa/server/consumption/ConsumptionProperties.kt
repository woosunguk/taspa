package com.taspa.server.consumption

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 소비 이벤트 수집 설정. @ConfigurationPropertiesScan 으로 자동 등록된다(TaspaServerApplication).
 */
@ConfigurationProperties(prefix = "taspa.consumption")
data class ConsumptionProperties(
    /**
     * ingest 1회 배치(events 배열) 크기 상한 — 무제한 배치로 인한 자원고갈(메모리·트랜잭션 장기화) 방지.
     * 초과 시 400 VALIDATION_ERROR. 운영에서 생산자 특성에 맞게 조정 가능. 기본 1000건.
     */
    val maxBatchSize: Int = 1000,
)
