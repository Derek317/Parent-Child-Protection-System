package app.family.igguard

import android.app.Notification
import android.app.NotificationManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** The parent's content-free "import_request", child side. Invented data only; no message text anywhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportRequestChildTest : UploadTestBase() {
    private lateinit var authority: FamilySecurity.SigningIdentity
    private val hour = 3_600_000L
    private val secret = "PRIVATE-WORDS-"

    @Before fun pinParent() {
        authority = FamilySecurity.newSigningIdentity()
        prefs.edit().putString("parent_command_public_key", authority.publicKey).commit()
        InstagramImport.dismiss(); InstagramImport.running = false
    }
    @After fun clearAfter() { InstagramImport.dismiss() }

    private fun command(ts: Long = System.currentTimeMillis(), id: String = UUID.randomUUID().toString()) =
        JSONObject().put("type", "import_request").put("scope", scope).put("command_id", id).put("ts_ms", ts)
    private fun deliver(inner: JSONObject, signed: Boolean = true, role: String = "child"): RelayConsumption {
        val payload = if (signed) FamilySecurity.signCommand(inner, authority.privateKey) else inner
        val line = JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(token, payload.toString()))
        return GuardEngine.processIncomingRelayLine(ctx, line.toString(), role, null)
    }
    /** The upload notice (id 7003); the ongoing disclosure (7001) is a different notification. */
    private fun notice(): Notification? = shadowOf(ctx.getSystemService(NotificationManager::class.java)).getNotification(7003)
    /** The card is up. */
    private fun shown() = RiskLocal.importRequestPending(ctx)
    /** The time of the last request shown is stored (it outlives the card, for the 20-hour limit). */
    private fun kept() = RiskLocal.importRequestAt(ctx) > 0
    private fun export(): ByteArray {
        val at = System.currentTimeMillis() - 2 * hour
        return JSONObject().put("participants", org.json.JSONArray().put(JSONObject().put("name", "Bea Friend")).put(JSONObject().put("name", "alex_teen")))
            .put("messages", org.json.JSONArray().put(JSONObject().put("sender_name", "Bea Friend").put("timestamp_ms", at).put("content", "invented test words")))
            .toString().toByteArray()
    }
    private fun assertNothing(why: String) {
        assertFalse(why, kept()); assertFalse(why, shown()); assertNull(why, notice())
    }

    // ---------- protocol ----------
    @Test fun validCommandAcceptsTheTypeAndAnExtraFieldChangesNothing() {
        val now = System.currentTimeMillis()
        assertTrue("import_request" in FamilySecurity.controlTypes)
        val read = FamilySecurity.readCommand(FamilySecurity.signCommand(command(now), authority.privateKey), authority.publicKey, scope, now)!!
        assertEquals("import_request", read.getString("type"))
        // A field the protocol does not define is not refused, and nothing reads it: what is kept is only the time.
        activate(ScoresMethod.UPLOAD)
        assertEquals(RelayConsumption.CONSUMED, deliver(command().put("text", "${secret}1").put("note", "${secret}2")))
        assertTrue(shown())
        val words = listOf(notice()!!).flatMap { listOfNotNull(it.extras.getCharSequence(Notification.EXTRA_TITLE), it.extras.getCharSequence(Notification.EXTRA_TEXT), it.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)) }.joinToString()
        assertFalse(words.contains(secret))
        assertTrue("nothing went back to the parent", outbox().none { it.toString().contains("import_request") || it.toString().contains(secret) })
    }
    @Test fun aForgedUnsignedExpiredOrOtherFamilyRequestIsRejectedLikeAnyCommand() {
        val now = System.currentTimeMillis()
        val attacker = FamilySecurity.newSigningIdentity()
        assertNull(FamilySecurity.readCommand(FamilySecurity.signCommand(command(now), attacker.privateKey), authority.publicKey, scope, now))
        assertNull(FamilySecurity.readCommand(FamilySecurity.signCommand(command(now), authority.privateKey), authority.publicKey, "other-family-scope", now))
        assertNull(FamilySecurity.readCommand(FamilySecurity.signCommand(command(now - 25 * hour), authority.privateKey), authority.publicKey, scope, now))
        assertNull(FamilySecurity.readCommand(FamilySecurity.signCommand(command(now + 3 * 60_000L), authority.privateKey), authority.publicKey, scope, now))
        assertNull(FamilySecurity.readCommand(FamilySecurity.signCommand(command(now, id = "not-a-uuid"), authority.privateKey), authority.publicKey, scope, now))
        activate(ScoresMethod.UPLOAD)
        assertEquals(RelayConsumption.REJECTED, deliver(command(), signed = false))
        val forged = JSONObject().put("event", "message").put("id", UUID.randomUUID().toString())
            .put("message", GuardEngine.encryptPayload(token, FamilySecurity.signCommand(command(), attacker.privateKey).toString()))
        assertEquals(RelayConsumption.REJECTED, GuardEngine.processIncomingRelayLine(ctx, forged.toString(), "child", null))
        assertNothing("forged or unsigned")
    }
    @Test fun replayAndPerTypeOrderFollowTheOtherControlTypes() {
        activate(ScoresMethod.UPLOAD)
        val now = System.currentTimeMillis()
        val first = command(now - 60_000L)
        assertEquals(RelayConsumption.CONSUMED, deliver(first))
        val at = RiskLocal.importRequestAt(ctx)
        RiskLocal.dismissImportRequest(ctx)
        assertEquals("the same command id is consumed again without effect", RelayConsumption.CONSUMED, deliver(first))
        assertFalse(shown())
        assertEquals("older than the last import_request", RelayConsumption.REJECTED, deliver(command(now - 120_000L)))
        assertFalse(shown())
        assertTrue(at > 0)
    }

    // ---------- child gate ----------
    @Test fun shownWhenUploadIsAgreedAndAnalysisIsOn() {
        activate(ScoresMethod.UPLOAD)
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertTrue(shown()); assertTrue(RiskLocal.importRequestPending(ctx)); assertNotNull(notice())
    }
    @Test fun whilePausedItIsConsumedAndNothingIsKeptOrShown() {
        activate(ScoresMethod.UPLOAD); assertTrue(RiskLocal.pause(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertNothing("paused")
    }
    @Test fun afterWithdrawalItIsConsumedAndNothingIsKeptOrShown() {
        activate(ScoresMethod.UPLOAD); assertTrue(RiskLocal.withdraw(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertNothing("withdrawn")
    }
    @Test fun underAutomaticCaptureItIsConsumedAndNothingIsKeptOrShown() {
        activate(ScoresMethod.CAPTURE)
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertNothing("capture")
    }
    @Test fun withNoPolicyOrNoAgreementNothingIsKeptOrShown() {
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertNothing("no policy")
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD)
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertNothing("not agreed yet")
    }
    @Test fun aParentOrUnconfiguredPhoneDoesNothingWithIt() {
        activate(ScoresMethod.UPLOAD)
        GuardEngine.setRole(ctx, "parent")
        assertEquals(RelayConsumption.REJECTED, deliver(command(), role = "parent"))
        assertFalse(RiskLocal.receiveImportRequest(ctx))
        assertNothing("parent")
        GuardEngine.setRole(ctx, "unconfigured")
        assertEquals(RelayConsumption.RETRY, deliver(command(), role = "unconfigured"))
        assertNothing("unconfigured")
    }

    // ---------- child rate limit ----------
    @Test fun aSecondRequestWithinTwentyHoursOfTheLastShownIsIgnored() {
        activate(ScoresMethod.UPLOAD)
        val t = System.currentTimeMillis()
        assertTrue(RiskLocal.receiveImportRequest(ctx, t))
        assertFalse(RiskLocal.receiveImportRequest(ctx, t + 19 * hour)); assertEquals(t, RiskLocal.importRequestAt(ctx))
        assertFalse("a time ahead of the clock counts as recent", RiskLocal.receiveImportRequest(ctx, t - hour)); assertEquals(t, RiskLocal.importRequestAt(ctx))
        assertTrue(RiskLocal.receiveImportRequest(ctx, t + 20 * hour)); assertEquals(t + 20 * hour, RiskLocal.importRequestAt(ctx))
        // Through the relay as well: a second signed request minutes later changes nothing.
        RiskLocal.wipeData(ctx); assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        val shownAt = RiskLocal.importRequestAt(ctx)
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertEquals(shownAt, RiskLocal.importRequestAt(ctx))
    }
    @Test fun afterOkOrAnImportASecondRequestWithinTwentyHoursIsStillIgnored() {
        activate(ScoresMethod.UPLOAD)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        // Shown, then "OK": the card goes, the time stays.
        assertEquals(RelayConsumption.CONSUMED, deliver(command())); assertTrue(shown()); assertNotNull(notice())
        val at = RiskLocal.importRequestAt(ctx)
        assertTrue(RiskLocal.dismissImportRequest(ctx)); manager.cancelAll()
        assertFalse(shown()); assertEquals("the time stays after OK", at, RiskLocal.importRequestAt(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertFalse("no card after OK", shown()); assertNull("no notification after OK", notice()); assertEquals(at, RiskLocal.importRequestAt(ctx))
        assertFalse(RiskLocal.receiveImportRequest(ctx, at + 19 * hour)); assertFalse(shown()); assertNull(notice())
        // Shown, then a successful import: the same.
        RiskLocal.wipeData(ctx); assertFalse(kept())
        assertEquals(RelayConsumption.CONSUMED, deliver(command())); assertTrue(shown())
        val at2 = RiskLocal.importRequestAt(ctx)
        assertEquals(ImportResult.OK, InstagramImport.run(ctx, java.io.ByteArrayInputStream(export()), "message_1.json").result); manager.cancelAll()
        assertFalse(shown()); assertEquals("the time stays after an import", at2, RiskLocal.importRequestAt(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        assertFalse("no card after an import", shown()); assertNull("no notification after an import", notice()); assertEquals(at2, RiskLocal.importRequestAt(ctx))
        // 20 hours after the last one shown, a new request shows again.
        assertTrue(RiskLocal.receiveImportRequest(ctx, at2 + 20 * hour)); assertTrue(shown()); assertNotNull(notice())
    }

    // ---------- what clears it ----------
    @Test fun okAndASuccessfulImportCloseTheCardAndTheWipeDeletesTheTime() {
        activate(ScoresMethod.UPLOAD)
        assertTrue(RiskLocal.receiveImportRequest(ctx)); RiskLocal.wipeData(ctx); assertFalse("wipe", kept()); assertFalse("wipe", shown())
        assertTrue(RiskLocal.receiveImportRequest(ctx))
        assertEquals(ImportResult.TOO_OLD, InstagramImport.run(ctx, java.io.ByteArrayInputStream(
            String(export()).replace(Regex("\"timestamp_ms\":\\d+"), "\"timestamp_ms\":${System.currentTimeMillis() - 20 * 24 * hour}").toByteArray()), "message_1.json").result)
        assertTrue("an import that stored nothing does not close it", shown())
        assertEquals(ImportResult.OK, InstagramImport.run(ctx, java.io.ByteArrayInputStream(export()), "message_1.json").result)
        assertFalse("import closes the card", shown()); assertTrue("import keeps the time", kept())
        RiskLocal.wipeData(ctx); assertFalse("wipe after import", kept())
        assertTrue(RiskLocal.receiveImportRequest(ctx)); RiskLocal.dismissImportRequest(ctx)
        assertFalse("OK closes the card", shown()); assertTrue("OK keeps the time", kept())
        RiskLocal.wipeData(ctx); assertFalse("wipe after OK", kept())
        assertTrue(RiskLocal.receiveImportRequest(ctx)); assertTrue("the closed flag went with the store", shown())
        assertTrue(RiskLocal.withdraw(ctx)); assertFalse("withdrawal wipes the time with the store", kept())
    }
    @Test fun nothingIsBlockedWhenItIsIgnored() {
        activate(ScoresMethod.UPLOAD)
        assertTrue(RiskLocal.receiveImportRequest(ctx, System.currentTimeMillis() - 30 * 24 * hour))
        assertTrue(RiskLocal.active(ctx)); assertTrue(InstagramImport.allowed(ctx))
        assertTrue(RiskLocal.pause(ctx)); assertTrue(RiskLocal.resume(ctx)); assertTrue(RiskLocal.withdraw(ctx))
    }

    // ---------- notification and card ----------
    @Test fun theNotificationIsContentFreeReplacesTheReminderAndOpensTheUploadCard() {
        activate(ScoresMethod.UPLOAD)
        RiskLocal.recordImport(ctx, System.currentTimeMillis() - 8 * 24 * hour); RiskLocal.remindImportIfDue(ctx)
        assertEquals(ScoresChildCopy.REMINDER_TITLE, notice()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(RelayConsumption.CONSUMED, deliver(command()))
        val n = notice()!!   // same id: the request replaced the reminder
        assertEquals(ScoresChildCopy.REQUEST_TITLE, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(ScoresChildCopy.REQUEST_TEXT, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals("child_disclosure", n.channelId); assertEquals(GuardEngine.NOTIFICATION_COLOR, n.color)
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(RiskLocal.OPEN_UPLOAD_ACTION, open.action); assertNull(open.data); assertNull(open.extras)
        assertEquals(MainActivity::class.java.name, open.component!!.className)
        val c = Robolectric.buildActivity(MainActivity::class.java, open).setup()
        try {
            val views = UiSupport.all(c.get().window.decorView)
            assertTrue(views.any { it.tag == "scores_import_request_card" }); assertTrue(views.any { it.tag == "scores_export_open" })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theCardSitsWithTheDecisionCardsOpensTheGuideAndOkDismissesIt() {
        activate(ScoresMethod.UPLOAD); assertTrue(RiskLocal.receiveImportRequest(ctx))
        val c = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), "overview") }
        try {
            val views = UiSupport.all(c.get().window.decorView)
            val card = views.single { it.tag == "scores_import_request_card" }
            assertTrue("above the status block", views.indexOf(card) < views.indexOfFirst { it.tag == "scores_status_block" })
            val texts = UiSupport.texts(card)
            assertEquals(listOf(ScoresChildCopy.REQUEST_CARD_TITLE, ScoresChildCopy.EXPORT_GUIDE_TITLE, ScoresChildCopy.OK_DISMISS), texts)
            views.single { it.tag == "scores_import_request_guide" }.performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(sheet.isShowing); assertTrue(UiSupport.all(sheet.window!!.decorView).any { it.tag == "scores_export_guide" })
            assertNull("the guide only opens the sheet", shadowOf(c.get()).nextStartedActivity)
            sheet.dismiss(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("reading the guide does not dismiss it", shown())
            UiSupport.all(c.get().window.decorView).single { it.tag == "scores_import_request_dismiss" }.performClick(); shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(shown())
            assertTrue(UiSupport.all(c.get().window.decorView).none { it.tag == "scores_import_request_card" })
            assertTrue("nothing is sent to the parent", outbox().none { it.toString().contains("import_request") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun theCardHidesWhileAnImportIsNotAllowed() {
        activate(ScoresMethod.UPLOAD); assertTrue(RiskLocal.receiveImportRequest(ctx)); assertTrue(RiskLocal.pause(ctx))
        assertFalse(RiskLocal.importRequestPending(ctx))
        val c = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), "overview") }
        try { assertTrue(UiSupport.all(c.get().window.decorView).none { it.tag == "scores_import_request_card" }) } finally { c.pause().stop().destroy() }
    }
}

/** Renders of the child's card, light and dark. Written under build/test-screenshots/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportRequestChildScreenshotTest : UploadTestBase() {
    @Test fun childCardLightAndDark() {
        for (bright in listOf(true, false)) {
            resetChild(); InstagramImport.dismiss(); GuardEngine.setBrightTheme(ctx, bright)
            activate(ScoresMethod.UPLOAD); RiskLocal.acknowledgeChangeNotice(ctx); assertTrue(RiskLocal.receiveImportRequest(ctx))
            val c = ShotSupport.open("overview")
            try { ShotSupport.shoot(c.get(), "import-request-child-card-" + if (bright) "light" else "dark", 360, 1400) } finally { c.pause().stop().destroy() }
        }
        GuardEngine.setBrightTheme(ctx, true)
    }
}
