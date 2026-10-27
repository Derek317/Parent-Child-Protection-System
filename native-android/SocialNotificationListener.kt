package com.safeguardfamily.app

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * Intercepts incoming Direct Message notifications from Instagram, TikTok, and Discord
 * on the Child's Android phone and forwards them into the Capacitor WebView (www/js/app.js).
 */
class SocialNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SafeGuardListener"
        const val ACTION_SOCIAL_DM_INTERCEPTED = "com.safeguardfamily.app.SOCIAL_DM_INTERCEPTED"

        // Monitored social media package names
        private val MONITORED_PACKAGES = mapOf(
            "com.instagram.android" to "Instagram",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok", // TikTok regional package variant
            "com.discord" to "Discord"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        val platformName = MONITORED_PACKAGES[packageName] ?: return

        val extras: Bundle = sbn.notification?.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        // Ignore empty or ongoing system/playback notifications
        if (rawTitle.isEmpty() || rawText.isEmpty()) return
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        Log.d(TAG, "Intercepted $platformName notification from $rawTitle")

        // Format sender handle
        val formattedSender = if (platformName != "Discord" && !rawTitle.startsWith("@")) {
            "@${rawTitle.replace(" ", "_").lowercase()}"
        } else {
            rawTitle
        }

        // Broadcast intent locally inside the app process so MainActivity / WebView receives it
        val payload = JSONObject().apply {
            put("platform", platformName)
            put("sender", formattedSender)
            put("message", rawText)
            put("packageName", packageName)
            put("timestamp", System.currentTimeMillis())
        }

        val intent = Intent(ACTION_SOCIAL_DM_INTERCEPTED).apply {
            setPackage(applicationContext.packageName)
            putExtra("payload_json", payload.toString())
        }
        applicationContext.sendBroadcast(intent)
    }
}