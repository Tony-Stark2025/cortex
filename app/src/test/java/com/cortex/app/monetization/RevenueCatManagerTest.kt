package com.cortex.app.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RevenueCatManagerTest {

    @Before
    fun setUp() {
        RevenueCatManager.resetProStatusForTesting()
    }

    @Test
    fun `redeemPromoCode activates Pro status for valid hackathon judge codes`() {
        assertFalse(RevenueCatManager.isProUser.value)

        var completed = false
        var resultSuccess = false
        var resultMsg = ""

        RevenueCatManager.redeemPromoCode("SHIPATON2026") { success, msg ->
            completed = true
            resultSuccess = success
            resultMsg = msg
        }

        assertTrue("Callback must complete", completed)
        assertTrue("Promo code SHIPATON2026 must succeed", resultSuccess)
        assertTrue("Message should indicate pro activation", resultMsg.contains("active", ignoreCase = true))
        assertTrue("RevenueCatManager stateFlow must reflect Pro status", RevenueCatManager.isProUser.value)
    }

    @Test
    fun `redeemPromoCode is case-insensitive and trims whitespace`() {
        var resultSuccess = false
        RevenueCatManager.redeemPromoCode("  shipaton2026  ") { success, _ ->
            resultSuccess = success
        }
        assertTrue(resultSuccess)
        assertTrue(RevenueCatManager.isProUser.value)
    }

    @Test
    fun `redeemPromoCode rejects invalid codes without altering Pro status`() {
        assertFalse(RevenueCatManager.isProUser.value)

        var resultSuccess = true
        var resultMsg = ""

        RevenueCatManager.redeemPromoCode("INVALID_CODE_XYZ") { success, msg ->
            resultSuccess = success
            resultMsg = msg
        }

        assertFalse("Invalid promo code must fail", resultSuccess)
        assertTrue("Failure message should be returned", resultMsg.contains("Invalid", ignoreCase = true))
        assertFalse("Pro status must remain false", RevenueCatManager.isProUser.value)
    }

    @Test
    fun `resetProStatusForTesting reverts Pro state to false`() {
        RevenueCatManager.redeemPromoCode("CORTEXPRO") { _, _ -> }
        assertTrue(RevenueCatManager.isProUser.value)

        RevenueCatManager.resetProStatusForTesting()
        assertFalse(RevenueCatManager.isProUser.value)
    }
}
