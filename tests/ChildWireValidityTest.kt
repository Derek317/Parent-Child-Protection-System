package app.family.igguard

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChildWireValidityTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
    }

    @Test fun aVeryConfidentLevelThreeScoreStaysInsideTheRangeTheParentAccepts() {
        val score = RiskScoring.fromLogits(floatArrayOf(-38.1f, -38.7f, -37.3f, 0.0f, -27.3f)).reportScore!!
        assertTrue(score.toString(), score in 0.0..100.0)
        val scope = UUID.randomUUID().toString()
        val state = JSONObject().put("policy_id", UUID.randomUUID().toString()).put("model", "valid").put("consent", "accepted").put("active", true)
            .put("paused", false).put("paused_at_ms", JSONObject.NULL).put("last_run_ms", System.currentTimeMillis()).put("scored_count", 1).put("unknown_threads", 0)
        val row = JSONObject().put("account_label", "alice").put("score", score).put("review_reason", JSONObject.NULL).put("scored_at_ms", System.currentTimeMillis() - 1000)
        val report = JSONObject().put("type", "scores_mode_report").put("sent_at_ms", System.currentTimeMillis()).put("state", state)
            .put("conversations", JSONArray().put(row)).put("scope", scope).put("command_id", UUID.randomUUID().toString())
        assertNotNull(ScoresReportCodec.parse(report, scope, System.currentTimeMillis()))
    }

    @Test fun deliveryHealthFieldsParseUnderDigitChangingLocales() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "bn-BD")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val now = System.currentTimeMillis()
                val report = DeliveryHealth.parse(DeliveryHealth.fields(7, now - 125 * 60_000L, true, now))
                assertEquals(tag, DeliveryHealth.Report(7, 125, true), report)
            }
        } finally { Locale.setDefault(saved) }
    }

    @Test fun overlappingCaptureAfterTheClockStepsBackStaysValid() {
        QrPairing.pairChild(ctx, FamilySecurity.newSigningIdentity())
        prefs.edit().putString("capture_policy_id", UUID.randomUUID().toString()).commit()
        assertTrue(GuardEngine.confirmFullCapture(ctx))
        fun scan(vararg texts: String) = GuardEngine.captureMessages(ctx, texts.map { CapturedText("peer", it, "incoming", author = "peer") },
            ConversationCapture.key("", "peer", "unknown"))
        assertTrue(scan("first line", "second line", "third line").isNotEmpty())
        FamilyEvidence(ctx).use { it.writableDatabase.execSQL("UPDATE capture_windows SET anchor=anchor+100000") }
        val second = scan("second line", "third line", "fourth line")
        assertTrue(second.isNotEmpty())
        for (r in second) assertTrue(r.toString(), ConversationCapture.valid(r))
    }
}
