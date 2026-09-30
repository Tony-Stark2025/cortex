package com.cortex.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.cortex.app.BuildConfig
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object OneSignalManager {

    private val ONESIGNAL_APP_ID: String = BuildConfig.ONESIGNAL_APP_ID.ifBlank {
        "35638374-74f0-4873-bc88-2324754e8e32"
    }
    private const val CHANNEL_ID = "cortex_spaced_repetition"
    private const val CHANNEL_NAME = "Spaced Repetition Study Reminders"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var isInitialized = false

    fun initialize(context: Context) {
        createNotificationChannel(context.applicationContext)
        try {
            OneSignal.Debug.logLevel = LogLevel.WARN
            OneSignal.initWithContext(context.applicationContext, ONESIGNAL_APP_ID)
            isInitialized = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Active recall & spaced-repetition intervals for your Cortex courses"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun scheduleSpacedRepetitionReminder(context: Context, subject: String) {
        val nextReviewEpochMs = System.currentTimeMillis() + 24L * 60L * 60L * 1000L // 24-hour SM-2 first interval
        scope.launch {
            try {
                val hasNotificationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                if (isInitialized) {
                    if (!hasNotificationPerm) {
                        OneSignal.Notifications.requestPermission(false)
                    }
                    OneSignal.User.addTag("last_studied_subject", subject)
                    OneSignal.User.addTag("spaced_repetition_pending", "true")
                    OneSignal.User.addTag("next_review_epoch_ms", nextReviewEpochMs.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun scheduleSpacedRepetitionReminder(subject: String) {
        if (!isInitialized) return
        try {
            val nextReviewEpochMs = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
            OneSignal.User.addTag("last_studied_subject", subject)
            OneSignal.User.addTag("spaced_repetition_pending", "true")
            OneSignal.User.addTag("next_review_epoch_ms", nextReviewEpochMs.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
