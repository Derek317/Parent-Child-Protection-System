package app.family.igguard

import android.app.Notification
import android.app.Person
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text a stranger can choose (a sender name, a notification title) never decides who the child is or who is trusted/flagged. Invented data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChildIdentitySpoofTest : UploadTestBase() {
    private fun post(id: Int, sender: String, text: String, title: String? = null) {
        val me = Person.Builder().setName("child display").build()
        val style = Notification.MessagingStyle(me).addMessage(text, System.currentTimeMillis() + id, Person.Builder().setName(sender).build())
        val n = Notification.Builder(ctx, "test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        if (title != null) n.extras.putCharSequence(Notification.EXTRA_TITLE, title)
        val c = Robolectric.buildService(IgNotificationListener::class.java).create()
        try { c.get().onNotificationPosted(StatusBarNotification("com.instagram.android", "com.instagram.android", id, null, 0, 0, 0, n, android.os.UserHandle.getUserHandleForUid(0), System.currentTimeMillis())) } finally { c.destroy() }
    }
    private fun captured() = outbox().filter { it.optString("type") == "captured_message" }
    private fun alerts(reason: String) = outbox().filter { it.optString("type") == "alert" && it.optString("reason") == reason }
    private fun alertOnly() { prefs.edit().remove("capture_local_ack_id").commit(); assertFalse(GuardEngine.fullCaptureEnabled(ctx)) }

    @Test fun selfWordsAsASenderNameAreAStranger() {
        for (name in listOf("You", "me", "你", "本人")) assertEquals(name, "stranger_message", GuardEngine.evaluateMessage(ctx, name, "hello there"))
        alertOnly(); post(1, "You", "hello from a stranger")
        assertEquals(1, alerts("stranger_message").size)
    }
    @Test fun aStrangerNamedAfterTheChildIsStillIncoming() {
        GuardEngine.setChildSelfHandle(ctx, "kid_handle")
        for ((i, name) in listOf("You", "kid_handle", "@KID_HANDLE").withIndex()) post(10 + i, name, "hello $i")
        assertEquals(3, captured().size)
        assertTrue(captured().all { it.getString("direction") == "incoming" })
    }
    @Test fun ownHandleNeedsExactEquality() {
        GuardEngine.setChildSelfHandle(ctx, "kid_handle")
        assertTrue(GuardEngine.isChildSelfHandle(ctx, "@Kid_Handle"))
        assertFalse(GuardEngine.isChildSelfHandle(ctx, "Bob (@kid_handle)"))
        assertFalse(GuardEngine.isChildSelfHandle(ctx, "You"))
    }
    @Test fun aNotificationTitleDoesNotSetTheChildHandleOrTouchTheLists() {
        GuardEngine.setTrustedHandles(ctx, listOf("friend")); prefs.edit().putString("flagged_offenders_csv", "bad_actor").commit()
        post(20, "friend", "hello", title = "[friend] friend")
        post(21, "x", "hello", title = "[bad_actor] x")
        assertEquals("", GuardEngine.getChildSelfHandle(ctx))
        assertEquals(setOf("friend"), GuardEngine.getTrustedHandles(ctx)); assertEquals(setOf("bad_actor"), GuardEngine.getFlaggedOffenders(ctx))
        assertTrue(outbox().none { it.optString("type") == "sync_child_handle" })
    }
    @Test fun settingTheHandleNeverRemovesAFlaggedEntry() {
        prefs.edit().putString("flagged_offenders_csv", "bad_actor").commit()
        GuardEngine.setChildSelfHandle(ctx, "bad_actor"); GuardEngine.setChildSelfHandle(ctx, "kid_handle")
        assertEquals(setOf("bad_actor"), GuardEngine.getFlaggedOffenders(ctx))
    }
    @Test fun flaggedMatchingIsExact() {
        prefs.edit().putString("flagged_offenders_csv", "bad_actor").commit()
        assertTrue(GuardEngine.isFlaggedOffender(ctx, "@Bad_Actor"))
        for (label in listOf("bad_actor fan", "not bad_actor", "Bob (@bad_actor)")) assertFalse(label, GuardEngine.isFlaggedOffender(ctx, label))
    }
    @Test fun aHandleChangeAlertsTheParentWithoutTheHandle() {
        GuardEngine.updateAutoDetectedChildHandle(ctx, "kid_one")
        GuardEngine.updateAutoDetectedChildHandle(ctx, "kid_one")
        GuardEngine.updateAutoDetectedChildHandle(ctx, "kid_two")
        val notices = alerts("tamper_attempt").filter { it.optString("sender") == "Account Guard" }
        assertEquals(2, notices.size)
        assertTrue(notices.none { n -> listOf("kid_one", "kid_two").any { n.getString("text").contains(it) || n.getString("sender").contains(it) } })
    }
}
