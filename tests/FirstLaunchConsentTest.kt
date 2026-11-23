package app.family.igguard

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.ComponentName
import android.content.pm.PackageManager
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowAccessibilityService
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FirstLaunchConsentTest.GrantShadow::class])
class FirstLaunchConsentTest {
    @Implements(AccessibilityService::class)
    class GrantShadow : ShadowAccessibilityService() {
        @Implementation fun disableSelf() { disabled = true }
        companion object { var disabled = false }
    }
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        GrantShadow.disabled = false
        CloudRelay.disconnect()
        ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE).edit().clear().commit()
        LocalVault.preferences(ctx).edit().clear().commit()
        ctx.deleteDatabase("family_evidence.db")
    }
    private fun root(a: MainActivity): View = a.findViewById(android.R.id.content)
    @Test fun firstLaunchRequiresEveryAcknowledgmentBeforeAcceptance() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val accept = root(a).findViewWithTag<Button>("consent_accept")
        assertNotNull("First launch must show Terms before role setup", accept)
        assertFalse(accept.isEnabled)
        val acknowledgments = listOf("consent_terms", "consent_transfer", "consent_family")
        for ((index, tag) in acknowledgments.withIndex()) {
            val box = root(a).findViewWithTag<CheckBox>(tag)
            assertFalse(box.isChecked)
            box.performClick()
            assertEquals(index == acknowledgments.lastIndex, accept.isEnabled)
        }
        controller.pause().stop().destroy()
    }
    @Test fun decliningReturnsHomeAndDoesNotAccept() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val decline = root(a).findViewWithTag<Button>("consent_decline")
        assertNotNull("A decline action is required", decline)
        decline.performClick()
        val home = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, home.action)
        assertTrue(home.categories.contains(Intent.CATEGORY_HOME))
        assertTrue(a.isFinishing)
        assertTrue(ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE).all.isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun aPreviouslyConfiguredPhoneIsInactiveWithoutFirstLaunchConsent() {
        GuardEngine.setRole(ctx, "child")
        assertEquals("child", GuardEngine.getRole(ctx))
        assertFalse("Android permissions or old role must not substitute for acceptance", GuardEngine.isChildMode(ctx))
        GuardEngine.setRole(ctx, "parent")
        assertFalse(GuardEngine.isParentMode(ctx))
    }
    @Test fun acceptanceIsEncryptedAndRelaunchDoesNotRepeatOrEnableExpandedCapture() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        assertEquals(AppConsent.terms(ctx), root(a).findViewWithTag<TextView>("consent_document").text.toString())
        AppConsent.acknowledgments.forEach { root(a).findViewWithTag<CheckBox>(it).performClick() }
        root(a).findViewWithTag<Button>("consent_accept").performClick()
        assertTrue(AppConsent.hasAccepted(ctx))
        val receipt = AppConsent.receipt(ctx)!!
        assertEquals(AppConsent.VERSION, receipt.version)
        assertEquals(BuildConfig.VERSION_NAME, receipt.appVersion)
        val digest = ctx.assets.open(AppConsent.TERMS_ASSET).use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
        assertEquals(digest, receipt.sha256)
        assertTrue(receipt.acceptedAt > 0)
        val raw = ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE).getString("receipt", "")!!
        assertTrue(LocalVault.isSealed(raw))
        assertFalse(raw.contains(AppConsent.VERSION))
        GuardEngine.setRole(ctx, "child")
        assertFalse(GuardEngine.fullCaptureEnabled(ctx))
        controller.pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).create().start()
        assertNull(root(second.get()).findViewWithTag<View>("consent_accept"))
        assertEquals(receipt, AppConsent.receipt(ctx))
        second.stop().destroy()
    }
    @Test fun normalUpdatePreservesOriginalRevisionInsteadOfSilentlyReplacingIt() {
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        val prefs = ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE)
        val json = JSONObject(LocalVault.open(ctx, "consent_receipt", prefs.getString("receipt", null)!!))
            .put("version", "earlier-accepted-revision").put("sha256", "a".repeat(64))
        prefs.edit().putString("receipt", LocalVault.seal(ctx, "consent_receipt", json.toString())).commit()
        val before = AppConsent.receipt(ctx)
        assertTrue(AppConsent.hasAccepted(ctx))
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        assertEquals(before, AppConsent.receipt(ctx))
        assertTrue(AppConsent.reviewText(ctx).contains("earlier-accepted-revision"))
    }
    @Test fun incompleteAcknowledgmentsCannotCreateReceipt() {
        try { AppConsent.accept(ctx, setOf("consent_terms")); fail("All acknowledgments are required") }
        catch (_: IllegalArgumentException) { }
        assertFalse(AppConsent.hasAccepted(ctx))
    }
    @Test fun backReturnsHomeAndNextLaunchStillRequiresConsent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().onBackPressed()
        assertTrue(shadowOf(controller.get()).nextStartedActivity.categories.contains(Intent.CATEGORY_HOME))
        assertFalse(AppConsent.hasAccepted(ctx))
        controller.pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(root(second.get()).findViewWithTag<View>("consent_accept"))
        second.pause().stop().destroy()
    }
    @Test fun corruptReceiptFailsClosedAndIsNotReplaced() {
        val prefs = ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE)
        prefs.edit().putString("receipt", "broken").commit()
        assertFalse(AppConsent.hasAccepted(ctx))
        try { AppConsent.accept(ctx, AppConsent.acknowledgments); fail("Retain unreadable receipt") }
        catch (_: SecurityException) { }
        assertEquals("broken", prefs.getString("receipt", null))
    }
    @Test fun unacceptedPhoneCannotQueueCapturePairOrConsumeIncomingMessages() {
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString())
        GuardEngine.setRole(ctx, "child")
        GuardEngine.sendAlertFromChild(ctx, "stranger_message", "contact", "private received content")
        assertEquals(0, GuardEngine.pendingDeliveryCount(ctx))
        assertTrue(GuardEngine.captureMessages(ctx, emptyList()).isEmpty())
        val invite = FamilySecurity.Invite(UUID.randomUUID().toString(), FamilySecurity.passwordVerifier("synthetic928"), FamilySecurity.newSigningIdentity().publicKey)
        assertFalse(GuardEngine.applyPairing(ctx, invite))
        assertEquals(RelayConsumption.RETRY, GuardEngine.processIncomingRelayLine(ctx, "{}", "child", null))
        GuardEngine.setRole(ctx, "parent")
        assertEquals(RelayConsumption.RETRY, GuardEngine.processIncomingRelayLine(ctx, "{}", "parent", null))
    }
    @Test fun relayCannotConnectEvenThroughItsDirectConnectionEntryPoint() {
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        GuardEngine.setRole(ctx, "parent")
        val token = GuardEngine.getOrCreateFamilyToken(ctx)
        val server = MockWebServer().apply { start() }
        val connection = CloudRelay.Connection(ctx, GuardEngine.captureRelaySession(ctx), token, server.url("/").toString())
        ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE).edit().clear().commit()
        val completed = CountDownLatch(1)
        try {
            var accepted = true
            connection.publish("test", "ciphertext", "urgent") { accepted = it.accepted; completed.countDown() }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertFalse(accepted)
            assertEquals(0, server.requestCount)
            assertFalse(CloudRelay.publishBlocking(ctx, token, "test", "ciphertext", "urgent").accepted)
        } finally { connection.close(); server.shutdown() }
    }
    @Test fun pairingDeepLinkIsHeldUntilConsentAndThenRefused() {
        val invite = FamilySecurity.Invite(UUID.randomUUID().toString(), FamilySecurity.passwordVerifier("synthetic928"), FamilySecurity.newSigningIdentity().publicKey)
        val link = "igguard://pair?version=43&token=${invite.token}&pw=${FamilySecurity.encode(invite.passwordVerifier)}&parent=${invite.parentPublicKey}"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertNotNull(root(controller.get()).findViewWithTag<View>("consent_accept"))
        assertEquals("unconfigured", GuardEngine.getRole(ctx))
        assertFalse(GuardEngine.applyPairing(ctx, invite))
        controller.pause().stop().destroy()
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        val accepted = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertEquals("unconfigured", GuardEngine.getRole(ctx))
        val dialog = MainActivity::class.java.getDeclaredField("activeSheet").apply { isAccessible = true }.get(accepted.get()) as android.app.Dialog
        assertTrue(dialog.isShowing)
        // After consent the held link is handled: refused with the plain message (design 2.8), never paired.
        assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("invite_refused"))
        accepted.pause().stop().destroy()
    }
    @Test fun existingQueuesAreHeldWithoutRetryMutationBeforeConsent() {
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString())
        GuardEngine.setRole(ctx, "child")
        val scope = GuardEngine.getChannelTopic(ctx)
        FamilyEvidence(ctx).use { db ->
            db.enqueue(scope, "old:0", "retained encrypted packet")
            val before = db.due(scope, System.currentTimeMillis(), "child")
            GuardEngine.retryPendingAlerts(ctx, force = true)
            GuardEngine.pollChannelOnce(ctx)
            assertEquals(before, db.due(scope, System.currentTimeMillis(), "child"))
            assertEquals(1, db.pendingCount(scope))
        }
        assertFalse(AppConsent.hasAccepted(ctx))
    }
    @Test fun anUpgradeKeepsChildComponentsAndAccessibilityGrantWhileCaptureIsGated() {
        GuardEngine.setRole(ctx, "child")
        val component = ComponentName(ctx, IgAccessibilityService::class.java)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, ctx.packageManager.getComponentEnabledSetting(component))
        val controller = Robolectric.buildService(IgAccessibilityService::class.java).create()
        IgAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(controller.get())
        assertFalse("Pending initial consent must not revoke the user's Android grant", GrantShadow.disabled)
        assertFalse(GuardEngine.isChildMode(ctx))
        controller.get().onAccessibilityEvent(android.view.accessibility.AccessibilityEvent.obtain().apply {
            packageName = GuardEngine.INSTAGRAM_PKG
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        })
        assertEquals(0, GuardEngine.pendingDeliveryCount(ctx))
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        assertTrue(GuardEngine.isChildMode(ctx))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, ctx.packageManager.getComponentEnabledSetting(component))
        controller.destroy()
    }
    @Test fun failedDiskCommitRollsBackMemoryAndRequiresSuccessfulRetry() {
        val real = ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE)
        var failNext = true
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun commit(): Boolean {
                        // Android publishes in-memory edits even when the subsequent disk write fails.
                        val result = editor.commit()
                        if (failNext) { failNext = false; return false }
                        return result
                    }
                }
            }
        }
        val context = object : ContextWrapper(ctx) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "ig_guard_consent") failing else super.getSharedPreferences(name, mode)
        }
        try { AppConsent.accept(context, AppConsent.acknowledgments); fail("Failed persistence must reject acceptance") }
        catch (_: SecurityException) { }
        assertFalse(AppConsent.hasAccepted(context))
        assertFalse(real.contains("receipt"))
        AppConsent.accept(context, AppConsent.acknowledgments)
        assertTrue(AppConsent.hasAccepted(context))
    }
    @Test fun pendingNotificationListenerUnbindsAndStartsItsHeartbeatAfterConsentRebind() {
        GuardEngine.setRole(ctx, "child")
        val name = ComponentName(ctx, IgNotificationListener::class.java)
        val component = name.flattenToString()
        shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).setNotificationListenerAccessGranted(name, true)
        android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", component)
        val controller = Robolectric.buildService(IgNotificationListener::class.java).create()
        val service = controller.get()
        val handler = IgNotificationListener::class.java.getDeclaredField("heartbeatHandler").apply { isAccessible = true }.get(service) as android.os.Handler
        val task = IgNotificationListener::class.java.getDeclaredField("periodicCheckRunnable").apply { isAccessible = true }.get(service) as Runnable
        service.onListenerConnected()
        assertEquals(1, shadowOf(service).unbindRequestCount)
        assertFalse(handler.hasCallbacks(task))
        assertEquals(component, android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners"))
        AppConsent.accept(ctx, AppConsent.acknowledgments)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount() > 0)
        service.onListenerConnected()
        assertTrue(handler.hasCallbacks(task))
        activity.pause().stop().destroy()
        controller.destroy()
    }
}
