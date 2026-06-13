package com.wmcho.puttingcaddie

import android.content.SharedPreferences

/**
 * Entitlement 단일 관리자.
 * Billing 연결 전에는 무료 기본값만 반환.
 * Phase 2에서 Billing 연동 시 purchase 상태로 slopeEnabled, proExpiry 갱신.
 */
class EntitlementManager(
    private val prefs: SharedPreferences,
    private val isDebuggable: Boolean
) {
    companion object {
        private const val KEY_TEST_OVERRIDE = "entitlement_test_override"
        private const val KEY_BILLING_ACTIVE = "entitlement_billing_active"
        private const val KEY_BILLING_EXPIRY = "entitlement_billing_expiry"
    }

    @Volatile
    private var _state: EntitlementState = createDefaultState()

    /** 현재 상태 (동기 조회) */
    val current: EntitlementState
        get() = _state

    /** slope 노출 가능 여부 */
    val canShowSlope: Boolean
        get() = current.canShowSlope

    private fun createDefaultState(): EntitlementState {
        val testOverride = isDebuggable && prefs.getBoolean(KEY_TEST_OVERRIDE, false)
        val billingActive = prefs.getBoolean(KEY_BILLING_ACTIVE, false)
        val expiry = prefs.getLong(KEY_BILLING_EXPIRY, 0L).takeIf { it > 0 }
        val slopeEnabled = billingActive && (expiry == null || expiry > System.currentTimeMillis())
        return EntitlementState(
            distanceEnabled = true,
            slopeEnabled = slopeEnabled,
            aimingEnabled = slopeEnabled,
            proExpiryMillis = expiry,
            isTestOverride = testOverride
        )
    }

    /**
     * 테스트용 Pro 강제 활성화 토글.
     * debug 빌드에서만 의미 있음.
     */
    fun setTestOverride(enabled: Boolean) {
        if (!isDebuggable) return
        prefs.edit().putBoolean(KEY_TEST_OVERRIDE, enabled).apply()
        refresh()
    }

    /** 테스트 override 현재 값 */
    fun isTestOverrideEnabled(): Boolean = prefs.getBoolean(KEY_TEST_OVERRIDE, false)

    /**
     * Billing 연동 시 호출. purchase 상태로 entitlement 갱신.
     * Phase 2에서 구현.
     */
    fun updateFromBilling(hasActiveSubscription: Boolean, expiryMillis: Long?) {
        prefs.edit()
            .putBoolean(KEY_BILLING_ACTIVE, hasActiveSubscription)
            .putLong(KEY_BILLING_EXPIRY, expiryMillis ?: 0L)
            .apply()
        val testOverride = prefs.getBoolean(KEY_TEST_OVERRIDE, false)
        val slopeEnabled = hasActiveSubscription && (expiryMillis == null || expiryMillis > System.currentTimeMillis())
        _state = EntitlementState(
            distanceEnabled = true,
            slopeEnabled = slopeEnabled,
            aimingEnabled = slopeEnabled,
            proExpiryMillis = expiryMillis,
            isTestOverride = testOverride
        )
    }

    /** 상태 새로 계산 (앱 시작/포그라운드 복귀 시) */
    fun refresh() {
        _state = createDefaultState()
        // Phase 2: 여기서 queryPurchasesAsync 결과 반영
    }
}
