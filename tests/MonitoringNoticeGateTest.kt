package app.family.igguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Capture runs only while the child can see the monitoring notice; heartbeats keep going and say why capture is off. Invented data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitoringNoticeGateTest : UploadTestBase() {
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private fun post(id: Int, text: String) {
        val me = Person.Builder().setName("child").build()
        val style = Notification.MessagingStyle(me).setConversationTitle("stranger").addMessage(text, System.currentTimeMillis() + id, Person.Builder().setName("stranger").build())
        val n = Notification.Builder(ctx, "test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        val c = Robolectric.buildService(IgNotificationListener::class.java).create()
        try { c.get().onNotificationPosted(StatusBarNotification("com.instagram.android", "com.instagram.android", id, null, 0, 0, 0, n, android.os.UserHandle.getUserHandleForUid(0), System.currentTimeMillis())) } finally { c.destroy() }
    }
    private fun captured() = outbox().filter { it.optString("type") == "captured_message" }
    private fun alerts() = outbox().filter { it.optString("type") == "alert" && it.optString("reason") == "stranger_message" }
    private fun observation() = CapturedText("stranger", "hello there", "incoming", "stranger", "", "notification", System.currentTimeMillis(), "k1")

    @Test fun captureRunsWhileTheNoticeCanShow() {
        assertTrue(GuardEngine.monitoringNoticeAllowed(ctx))
        post(1, "hello there"); assertEquals(1, captured().size)
    }
    @Test fun appNotificationsOffStopsEveryCaptureEntryPoint() {
        shadowOf(nm).setNotificationsEnabled(false)
        assertFalse(GuardEngine.monitoringNoticeAllowed(ctx))
        post(2, "hello there")
        assertTrue(GuardEngine.captureMessages(ctx, listOf(observation())).isEmpty())
        assertTrue(captured().isEmpty())
    }
    @Test fun aBlockedNoticeChannelStopsCapture() {
        nm.createNotificationChannel(NotificationChannel("child_disclosure", "x", NotificationManager.IMPORTANCE_NONE))
        assertFalse(GuardEngine.monitoringNoticeAllowed(ctx))
        post(3, "hello there"); assertTrue(captured().isEmpty())
    }
    @Test fun alertOnlyModeIsGatedToo() {
        prefs.edit().remove("capture_local_ack_id").commit(); assertFalse(GuardEngine.fullCaptureEnabled(ctx))
        post(4, "first ordinary hello"); assertEquals(1, alerts().size)
        shadowOf(nm).setNotificationsEnabled(false)
        post(5, "second ordinary hello"); assertEquals(1, alerts().size)
    }
    @Test fun heartbeatsContinueAndTellTheParentWithoutContent() {
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertFalse(heartbeats().single().getString("text").contains(GuardEngine.NOTICE_OFF_MARK))
        shadowOf(nm).setNotificationsEnabled(false)
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        assertEquals(2, heartbeats().size)
        assertTrue(heartbeats().any { it.getString("text").contains(GuardEngine.NOTICE_OFF_MARK) })
    }
}
