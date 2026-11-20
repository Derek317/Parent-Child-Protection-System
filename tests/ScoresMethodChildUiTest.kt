package app.family.igguard

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.*
import java.security.MessageDigest
import java.util.UUID

/** The child's pages for the collection method: what the parent chose is stated before the child decides, and upload says the permissions may go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScoresMethodChildUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private var original: ProxySelector? = null
    private val tinyBytes = "tiny model file for tests".toByteArray()

    @Before fun reset() {
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit()
        ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key", FamilySecurity.newSigningIdentity().publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
    }
    @After fun cleanup() {
        RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
        ProxySelector.setDefault(original); original = null
    }
    private fun installModel() {
        val sha = MessageDigest.getInstance("SHA-256").digest(tinyBytes).joinToString("") { "%02x".format(it) }
        RiskModelStore.expectation = RiskModelStore.Expectation(tinyBytes.size.toLong(), sha)
        assertEquals(RiskImportResult.OK, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), {}, tinyBytes.size.toLong(), sha))
    }
    private fun launch(section: String): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), section) }
    private fun screen(c: ActivityController<MainActivity>) = UiSupport.texts(c.get().window.decorView).joinToString("\n")

    @Test fun theConsentPageForUploadStatesTheMethodAndSaysTheTeenMaySwitchTheAccessesOff() {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD)
        val c = launch("scores_consent")
        try {
            val text = screen(c)
            for (needle in listOf("手動上載", "manual upload", "Notification access", "Accessibility", "通知存取", "無障礙", "never copied")) assertTrue(needle, text.contains(needle))
            assertFalse("no capture warning on the upload page", text.contains("Terms of Use"))
            assertTrue(UiSupport.all(c.get().window.decorView).any { it.tag == "scores_agree" })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theConsentPageForCaptureRepeatsAShortenedWarning() {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.CAPTURE)
        val c = launch("scores_consent")
        try {
            val text = screen(c)
            for (needle in listOf("自動擷取", "automatic capture", "Terms of Use", "違反 Instagram 的使用條款", "未獲 Meta 批准", "no Meta approval")) assertTrue(needle, text.contains(needle))
            assertFalse(text.contains("never copied"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun aMethodChangeAsksAgainAndSaysTheEarlierMethodStaysUntilTheChildAgrees() {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.CAPTURE); assertTrue(RiskLocal.accept(ctx))
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD)
        val c = launch("overview")
        try {
            val views = UiSupport.all(c.get().window.decorView)
            assertEquals(1, views.count { it.tag == "scores_consent_card" })
            val text = screen(c)
            assertTrue(text.contains(ScoresChildCopy.METHOD_CARD_BODY.lineSequence().first())); assertFalse(text.contains(ScoresChildCopy.UPLOAD_ON_NOTE.lineSequence().first()))
        } finally { c.pause().stop().destroy() }
    }
    private fun exportJson(): ByteArray {
        val now = System.currentTimeMillis()
        val messages = org.json.JSONArray().put(org.json.JSONObject().put("sender_name", "Bea Friend").put("timestamp_ms", now - 3_600_000L).put("content", "see you tomorrow"))
            .put(org.json.JSONObject().put("sender_name", "Alex Teen").put("timestamp_ms", now - 7_200_000L).put("content", "hi Bea"))
        return org.json.JSONObject().put("participants", org.json.JSONArray().put(org.json.JSONObject().put("name", "Bea Friend")).put(org.json.JSONObject().put("name", "Alex Teen"))).put("messages", messages).toString().toByteArray()
    }
    private fun count(table: String) = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun answerPick(a: MainActivity, code: Int, ok: Boolean, uri: android.net.Uri?) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.Intent::class.java).apply { isAccessible = true }
            .invoke(a, code, if (ok) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, android.content.Intent().setData(uri))
    }

    @Test fun theImportActionIsOfferedOnlyInUploadMethodAfterTheChildAgreed() {
        installModel()
        // Not agreed yet: the parent asked for upload, the child has not answered.
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD)
        var c = launch("overview")
        try { assertTrue(UiSupport.all(c.get().window.decorView).none { it.tag == "scores_export_row" }) } finally { c.pause().stop().destroy() }
        assertTrue(RiskLocal.accept(ctx))
        c = launch("overview")
        try {
            val rows = UiSupport.all(c.get().window.decorView).filter { it.tag == "scores_export_row" }
            assertEquals(1, rows.size)
            rows.single().performClick()
            val started = org.robolectric.Shadows.shadowOf(c.get()).nextStartedActivityForResult
            assertEquals(9033, started.requestCode); assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
            assertTrue("no storage permission is asked for", c.get().packageManager.getPackageInfo(c.get().packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().none { it.contains("STORAGE") })
        } finally { c.pause().stop().destroy() }
        reset(); installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.CAPTURE); assertTrue(RiskLocal.accept(ctx))
        c = launch("overview")
        try { assertTrue("capture method has no import action", UiSupport.all(c.get().window.decorView).none { it.tag == "scores_export_row" }) } finally { c.pause().stop().destroy() }
    }
    @Test fun aPickedFileIsReadAsAStreamImportedAndTheResultIsShown() {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD); assertTrue(RiskLocal.accept(ctx))
        InstagramImport.runner = { it.run() }; InstagramImport.dismiss()
        val good = android.net.Uri.parse("content://igguard.test/export/message_1.json"); val bad = android.net.Uri.parse("content://igguard.test/export/broken.json")
        val shadow = org.robolectric.Shadows.shadowOf(ctx.contentResolver)
        shadow.registerInputStream(good, ByteArrayInputStream(exportJson())); shadow.registerInputStream(bad, ByteArrayInputStream("{ broken".toByteArray()))
        val c = launch("overview")
        try {
            answerPick(c.get(), 9033, false, good)
            assertEquals("a cancelled picker imports nothing", 0, count("risk_store")); assertNull(InstagramImport.last)
            answerPick(c.get(), 9033, true, bad)
            assertEquals(ImportResult.MALFORMED, InstagramImport.last!!.result); assertEquals(0, count("risk_store"))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val views = UiSupport.all(c.get().window.decorView)
            assertTrue(UiSupport.texts(c.get().window.decorView).any { it.contains("Nothing was stored") }); assertEquals(1, views.count { it.tag == "scores_export_result" })
            views.single { it.tag == "scores_export_dismiss" }.performClick()
            assertNull(InstagramImport.last)
            answerPick(c.get(), 9033, true, good)
            assertEquals(ImportResult.OK, InstagramImport.last!!.result); assertEquals(2, count("risk_store"))
        } finally { InstagramImport.runner = { Thread(it, "Instagram import").start() }; InstagramImport.dismiss(); c.pause().stop().destroy() }
    }
    @Test fun anImportThatIsNotAllowedSaysSoAndStoresNothing() {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD); assertTrue(RiskLocal.accept(ctx)); assertTrue(RiskLocal.pause(ctx))
        val uri = android.net.Uri.parse("content://igguard.test/export/message_1.json")
        org.robolectric.Shadows.shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(exportJson()))
        val c = launch("overview")
        try {
            answerPick(c.get(), 9033, true, uri)
            assertEquals(ImportResult.NOT_ALLOWED, InstagramImport.last!!.result); assertEquals(0, count("risk_store"))
        } finally { InstagramImport.dismiss(); c.pause().stop().destroy() }
    }
    @Test fun afterAgreeingToUploadTheOverviewSaysTheAccessesMayGo() {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD); assertTrue(RiskLocal.accept(ctx))
        val c = launch("overview")
        try {
            val text = screen(c)
            assertTrue(text.contains(ScoresChildCopy.UPLOAD_ON_NOTE.lineSequence().first()))
            assertTrue(UiSupport.all(c.get().window.decorView).any { it.tag == "scores_upload_note" })
        } finally { c.pause().stop().destroy() }
        reset(); installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.CAPTURE); assertTrue(RiskLocal.accept(ctx))
        val d = launch("overview")
        try { assertTrue("capture method says nothing of the kind", UiSupport.all(d.get().window.decorView).none { it.tag == "scores_upload_note" }) } finally { d.pause().stop().destroy() }
    }
}
