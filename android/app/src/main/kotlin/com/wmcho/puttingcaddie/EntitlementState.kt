package com.wmcho.puttingcaddie

/**
 * Pro 기능 접근 권한 상태.
 * SSOT: UI는 이 상태만 보고 slope/aiming 노출 여부를 결정한다.
 *
 * 거리 측정은 항상 허용. 경사/에이밍은 Pro 또는 test override 시에만 허용.
 */
data class EntitlementState(
    /** 항상 true. 거리 측정은 무료. */
    val distanceEnabled: Boolean = true,

    /** Pro 구독 또는 test override일 때 true */
    val slopeEnabled: Boolean = false,

    /** 향후 Pro 에이밍 가이드 (현재 미사용) */
    val aimingEnabled: Boolean = false,

    /** Pro 구독 만료 시각. null = 무료/미구독 */
    val proExpiryMillis: Long? = null,

    /**
     * 내부 테스트용 강제 Pro 활성화.
     * true이면 slopeEnabled를 billing 없이 활성화.
     */
    val isTestOverride: Boolean = false
) {
    /** slope 기능 노출 여부. billing Pro 또는 test override */
    val canShowSlope: Boolean
        get() = slopeEnabled || isTestOverride

    /** Pro 구독 유효 여부 (만료 전) */
    val isProActive: Boolean
        get() = slopeEnabled && (proExpiryMillis == null || proExpiryMillis > System.currentTimeMillis())
}
