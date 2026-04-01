package com.wmcho.puttingcaddie

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * Google Play Billing 관리자.
 * puttingcaddy_pro 구독 상품의 구매 상태를 조회하고 EntitlementManager에 반영.
 *
 * Phase 2: 배관만 설치. 구매 UI는 SHOW_PRO_PAYWALL로 별도 제어.
 */
class BillingManager(
    private val context: Context,
    private val entitlementManager: EntitlementManager
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val SUBSCRIPTION_ID = "puttingcaddy_pro"
    }

    private var billingClient: BillingClient? = null

    /** Billing 연결 여부 */
    val isReady: Boolean
        get() = billingClient?.isReady == true

    fun startConnection() {
        if (billingClient != null) return
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient!!.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing 연결 성공")
                    queryAndApplyPurchases()
                } else {
                    Log.w(TAG, "Billing 연결 실패: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing 연결 끊김")
            }
        })
    }

    fun queryPurchasesAsync() {
        queryAndApplyPurchases()
    }

    private fun queryAndApplyPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { billingResult: BillingResult, purchases: List<Purchase> ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var hasActive = false
                var expiryMillis: Long? = null

                for (purchase in purchases) {
                    if (purchase.products.contains(SUBSCRIPTION_ID)) {
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            hasActive = true
                            try {
                                val json = JSONObject(purchase.originalJson)
                                val exp = json.optLong("expiryTimeMillis", 0L)
                                if (exp > 0L && (expiryMillis == null || exp > expiryMillis)) {
                                    expiryMillis = exp
                                }
                            } catch (_: Exception) { /* ignore */ }
                            if (!purchase.isAcknowledged) {
                                acknowledgePurchase(purchase)
                            }
                        }
                    }
                }
                entitlementManager.updateFromBilling(hasActive, expiryMillis)
                Log.d(TAG, "구매 상태 반영: hasActive=$hasActive expiry=$expiryMillis")
            } else {
                Log.w(TAG, "queryPurchases 실패: ${billingResult.debugMessage}")
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "acknowledge 성공")
            } else {
                Log.w(TAG, "acknowledge 실패: ${result.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }
            }
            queryAndApplyPurchases()
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.w(TAG, "구매 업데이트: ${result.debugMessage}")
        }
    }

    /**
     * 구매 플로우 실행. SHOW_PRO_PAYWALL=true일 때만 호출.
     * basePlanId: "monthly-prepaid" | "season-3m-prepaid"
     */
    fun launchPurchaseFlow(activity: Activity, basePlanId: String, productDetails: ProductDetails?): Boolean {
        val client = billingClient ?: return false
        if (!client.isReady) return false
        val details = productDetails ?: return false

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        )
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()
        val flowResult = client.launchBillingFlow(activity, params)
        return flowResult.responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * 상품 정보 조회. CTA 버튼 노출 시 가격 표시용.
     * 콜백 기반으로 호출.
     */
    fun queryProductDetails(callback: (ProductDetails?) -> Unit) {
        val client = billingClient ?: run { callback(null); return }
        if (!client.isReady) { callback(null); return }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SUBSCRIPTION_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                callback(productDetailsList?.firstOrNull())
            } else {
                Log.w(TAG, "queryProductDetails 실패: ${billingResult.debugMessage}")
                callback(null)
            }
        }
    }

    fun endConnection() {
        billingClient?.endConnection()
        billingClient = null
    }
}
