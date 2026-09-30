package com.cortex.app.monetization

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.cortex.app.BuildConfig
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.getCustomerInfoWith
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RevenueCatManager {

    private val GALAXY_API_KEY: String = BuildConfig.REVENUECAT_API_KEY.ifBlank {
        "test_QZYEbrxJrDCBvdxPTtzfwtNxNcq"
    }
    private const val PRO_ENTITLEMENT_ID = "pro_scholar"
    private const val PREFS_NAME = "cortex_monetization_prefs"
    private const val KEY_PRO_ACTIVE = "is_pro_scholar_active"

    private var prefs: SharedPreferences? = null
    @Volatile private var isConfigured = false

    private val _isProUser = MutableStateFlow(false)
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.remove("is_sandbox_trial_override")?.apply()
        _isProUser.value = prefs?.getBoolean(KEY_PRO_ACTIVE, false) ?: false

        try {
            val config = PurchasesConfiguration.Builder(context.applicationContext, GALAXY_API_KEY)
                .build()
            Purchases.configure(config)
            isConfigured = true

            Purchases.sharedInstance.getCustomerInfoWith(
                onError = { /* Preserve persisted local state in offline environments */ },
                onSuccess = { info -> updateProStatus(info) }
            )
        } catch (e: Exception) {
            isConfigured = false
        }
    }

    private fun updateProStatus(customerInfo: CustomerInfo) {
        val activeEntitlement = customerInfo.entitlements[PRO_ENTITLEMENT_ID]?.isActive == true
        _isProUser.value = activeEntitlement
        prefs?.edit()?.putBoolean(KEY_PRO_ACTIVE, activeEntitlement)?.apply()
    }

    fun restorePurchases(onComplete: (Boolean) -> Unit) {
        if (!isConfigured) {
            onComplete(_isProUser.value)
            return
        }

        try {
            Purchases.sharedInstance.restorePurchasesWith(
                onError = {
                    onComplete(_isProUser.value)
                },
                onSuccess = { customerInfo ->
                    updateProStatus(customerInfo)
                    onComplete(_isProUser.value)
                }
            )
        } catch (_: Exception) {
            onComplete(_isProUser.value)
        }
    }

    fun redeemPromoCode(code: String, onComplete: (Boolean, String) -> Unit) {
        val normalized = code.trim().uppercase()
        val validCodes = setOf("SHIPATON2026", "SHIPATON", "CORTEXPRO", "GALAXY2026", "DEVPOST2026")
        if (validCodes.contains(normalized)) {
            _isProUser.value = true
            prefs?.edit()?.putBoolean(KEY_PRO_ACTIVE, true)?.apply()
            onComplete(true, "Promo code applied! Cortex Pro Scholar is now active.")
        } else {
            onComplete(false, "Invalid promo code. Please check and try again.")
        }
    }

    fun resetProStatusForTesting() {
        _isProUser.value = false
        prefs?.edit()?.putBoolean(KEY_PRO_ACTIVE, false)?.apply()
    }

    fun purchaseProPlan(activity: Activity?, onComplete: (Boolean) -> Unit) {
        if (!isConfigured || activity == null) {
            onComplete(false)
            return
        }

        try {
            Purchases.sharedInstance.getOfferingsWith(
                onError = {
                    onComplete(false)
                },
                onSuccess = { offerings ->
                    val pkg = offerings.current?.availablePackages?.firstOrNull()
                    if (pkg != null) {
                        Purchases.sharedInstance.purchaseWith(
                            PurchaseParams.Builder(activity, pkg).build(),
                            onError = { _, _ ->
                                onComplete(false)
                            },
                            onSuccess = { _, customerInfo ->
                                updateProStatus(customerInfo)
                                onComplete(_isProUser.value)
                            }
                        )
                    } else {
                        onComplete(false)
                    }
                }
            )
        } catch (_: Exception) {
            onComplete(false)
        }
    }
}
