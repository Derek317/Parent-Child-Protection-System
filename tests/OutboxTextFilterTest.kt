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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OutboxTextFilterTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val parent = FamilySecurity.newSigningIdentity()

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
    }

    private fun innerTypeByEvent(token: String): Map<String, String> =
        FamilyEvidence(ctx).use { it.due(GuardEngine.getChannelTopicForToken(token), Long.MAX_VALUE) }
            .associate { frame ->
                val outer = GuardEngine.decryptPayload(token, frame.wire)!!
                val inner = outer.optString("child_signed").takeIf { it.isNotEmpty() }?.let { JSONObject(it) } ?: outer
                frame.id.substringBeforeLast(':') to inner.optString("type").let { t -> if (t == "alert") "alert:" + inner.optString("reason") else t }
            }

    private fun queueScoresReport() {
        val state = JSONObject().put("policy_id", UUID.randomUUID().toString()).put("model", "valid").put("consent", "accepted").put("active", true)
            .put("paused", false).put("paused_at_ms", JSONObject.NULL).put("last_run_ms", System.currentTimeMillis()).put("scored_count", 1).put("unknown_threads", 0)
        val row = JSONObject().put("account_label", "alice").put("score", 42.0).put("review_reason", JSONObject.NULL).put("scored_at_ms", System.currentTimeMillis() - 1000)
        assertNotNull(GuardEngine.queueScoresReport(ctx, JSONObject().put("type", "scores_mode_report").put("sent_at_ms", System.currentTimeMillis())
            .put("state", state).put("conversations", JSONArray().put(row))))
    }

    private fun pairedChildWithQueuedText(): String {
        val token = QrPairing.pairChild(ctx, parent).token
        prefs.edit().putString("capture_policy_id", UUID.randomUUID().toString()).commit()
        assertTrue(GuardEngine.confirmFullCapture(ctx))
        assertEquals(1, GuardEngine.captureMessages(ctx, listOf(CapturedText("stranger", "send nudes", "incoming", author = "stranger", sourceKey = "k1"))).size)
        assertTrue(innerTypeByEvent(token).values.toString(), "captured_message" in innerTypeByEvent(token).values)
        return token
    }

    @Test fun acceptingScoresModeDiscardsQueuedTextOnAQrPairedChild() {
        val token = pairedChildWithQueuedText()
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true); assertTrue(RiskLocal.accept(ctx))
        assertFalse(innerTypeByEvent(token).values.toString(), "captured_message" in innerTypeByEvent(token).values)
    }

    @Test fun changingCaptureOptionsKeepsTamperAndScoreRecordsButDropsText() {
        val token = pairedChildWithQueuedText()
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
        queueScoresReport()
        val before = innerTypeByEvent(token).values
        assertTrue(before.toString(), "alert:tamper_attempt" in before && "scores_mode_report" in before)
        GuardEngine.setCaptureOptions(ctx, false, false)
        val after = innerTypeByEvent(token).values
        assertFalse(after.toString(), "captured_message" in after)
        assertTrue(after.toString(), "alert:tamper_attempt" in after && "scores_mode_report" in after)
        assertEquals(before.count { it != "captured_message" }, after.size)
    }
}
