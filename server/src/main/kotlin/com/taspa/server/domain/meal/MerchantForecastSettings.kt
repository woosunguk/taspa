package com.taspa.server.domain.meal

import com.taspa.server.forecast.ForecastSignals
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * 매장별 예측 신호 설정(V41). 행이 없으면 **코드 기본값**([ForecastSignals] 의 기본 생성자)과 같다 —
 * 저장하지 않은 매장의 동작이 도입 전과 정확히 같아야 한다.
 */
@Entity
@Table(name = "merchant_forecast_settings")
class MerchantForecastSettings(
    @Id
    @Column(name = "merchant_id")
    val merchantId: UUID,
    @Column(name = "headcount_adjust", nullable = false)
    var headcountAdjust: Boolean = true,
    @Column(name = "absence_aware", nullable = false)
    var absenceAware: Boolean = true,
    @Column(name = "holiday_aware", nullable = false)
    var holidayAware: Boolean = true,
    @Column(name = "event_aware", nullable = false)
    var eventAware: Boolean = false,
    @Column(name = "menu_aware", nullable = false)
    var menuAware: Boolean = false,
    @Column(name = "nowcast", nullable = false)
    var nowcast: Boolean = true,
    @Column(name = "method_selection", nullable = false)
    var methodSelection: Boolean = false,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    fun toSignals(): ForecastSignals =
        ForecastSignals(
            headcountAdjust = headcountAdjust,
            absenceAware = absenceAware,
            holidayAware = holidayAware,
            eventAware = eventAware,
            menuAware = menuAware,
            nowcast = nowcast,
            methodSelection = methodSelection,
        )
}

interface MerchantForecastSettingsRepository : JpaRepository<MerchantForecastSettings, UUID>
