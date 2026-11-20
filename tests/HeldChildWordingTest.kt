package app.family.igguard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** While capture is held the child's own screens must not say capture is running or that the parent still receives messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeldChildWordingTest : UploadTestBase() {
    @Test fun theOngoingNoticeSaysCaptureIsHeldWhetherOrNotAPolicyIsOn() {
        activate(ScoresMethod.UPLOAD); switchOff()
        assertEquals(ScoresChildCopy.HELD_STATUS, RiskLocal.disclosure(ctx, "BASE-CAPTURE-RUNNING"))
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, ScoresMethod.CAPTURE)   // asked again, not agreed yet
        assertTrue(RiskLocal.captureHeld(ctx)); assertEquals(ScoresChildCopy.HELD_STATUS, RiskLocal.disclosure(ctx, "BASE-CAPTURE-RUNNING"))
        RiskLocal.releaseCaptureHold(ctx); assertNotEquals(ScoresChildCopy.HELD_STATUS, RiskLocal.disclosure(ctx, "BASE-CAPTURE-RUNNING"))
    }
    @Test fun theChildStatusLineAndTheWaitingLineDoNotPromiseCapture() {
        activate(ScoresMethod.UPLOAD); switchOff()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            UiSupport.nav(c.get(), "overview")
            val text = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            assertTrue(text, text.contains(ScoresChildCopy.HELD_STATUS))
        } finally { c.pause().stop().destroy() }
        val waiting = ScoresChildCopy.statusLines(ScoresChildCopy.StatusState.WAITING, "none", held = true).joinToString("\n")
        assertFalse(waiting, waiting.contains("still receives messages")); assertTrue(waiting, waiting.contains("stays off"))
        assertTrue(ScoresChildCopy.statusLines(ScoresChildCopy.StatusState.WAITING, "none").joinToString("\n").contains("still receives messages"))
    }
    @Test fun childHeaderFollowsTheCollectionMethod() {
        fun header(): String { val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try { UiSupport.nav(c.get(), "overview"); return UiSupport.texts(c.get().window.decorView).joinToString("\n") } finally { c.pause().stop().destroy() } }
        activate(ScoresMethod.CAPTURE); header().let { assertTrue(it, it.contains("雙向擷取已啟用")); assertFalse(it, it.contains("不讀取 Instagram")) }
        activate(ScoresMethod.UPLOAD); header().let { assertTrue(it, it.contains("手動上載 · 不讀取 Instagram")); assertFalse(it, it.contains("未授權") || it.contains("只擷取傳入警報")) }
        switchOff(); header().let { assertTrue(it, it.contains("擷取已停止，等你決定 · 不讀取 Instagram")); assertFalse(it, it.contains("未授權")) }
    }
    @Test fun storedMessagesAreWipedOnceWhenAnUnreadableReceiptIsFirstSeen() {
        activate(ScoresMethod.CAPTURE)
        GuardEngine.captureMessages(ctx, listOf(CapturedText("Pal", "hello", "incoming", "Pal", "", "notification", System.currentTimeMillis() - 3000, "k1", false, "direct")))
        assertEquals(1, count("risk_store"))
        receipts.edit().putString("receipt", "not a sealed receipt").commit()
        assertEquals(RiskCapture.UPLOAD, RiskLocal.captureMode(ctx)); assertEquals("wiped without waiting for the daily cleanup", 0, count("risk_store"))
        // Once only: the flag, not the state, decides, so a later call does not keep wiping.
        FamilyEvidence(ctx).use { it.storeRiskMessage(scope, "e1", "pal", "Pal", "incoming", "later", System.currentTimeMillis() - 1000, System.currentTimeMillis() - 1000, 0L, System.currentTimeMillis()) }
        RiskLocal.captureMode(ctx); assertEquals(1, count("risk_store"))
    }
}
