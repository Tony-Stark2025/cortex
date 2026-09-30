package com.cortex.app

import android.app.Application
import com.cortex.app.monetization.RevenueCatManager
import com.cortex.app.notifications.OneSignalManager

class CortexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize RevenueCat with Samsung Galaxy Store configuration
        RevenueCatManager.initialize(this)

        // Initialize OneSignal for spaced-repetition study reminders
        OneSignalManager.initialize(this)
    }
}
