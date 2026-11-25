package app.family.igguard

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * key_rotated must reach a child that was offline for days, as long as the parent's outbox and the relay
 * can still hand them over (3 + 7 days), without a replay and without acting on a later pairing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommandLifetimeTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val day = 86_400_000L
    private val parent = FamilySecurity.newSigningIdentity()
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    private val role get() = GuardEngine.getRole(ctx)

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
    }
    private fun command(type: String, age: Long) = JSONObject().put("type", type).put("scope", GuardEngine.getChannelTopic(ctx))
        .put("command_id", UUID.randomUUID().toString()).put("ts_ms", System.currentTimeMillis() - age)
    private fun rotation(age: Long, next: String = UUID.randomUUID().toString()) =
        command("key_rotated", age).put("new_token", next).put("new_pin", FamilySecurity.passwordVerifier(QrPairing.PASSWORD))
    /** The record as the parent queues it (signed, relay_control wrapper). */
    private fun record(cmd: JSONObject) = Pairing.commandRecord(FamilySecurity.signCommand(cmd, parent.privateKey), cmd.getString("command_id"))
    /** The relay hands [record] over under a fresh relay id, encrypted with [under]. */
    private fun deliver(record: JSONObject, under: String = token) = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message")
        .put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(under, record.toString())).toString(), "child", null)
    private fun laterCommand() = assertEquals("a later command prunes the 24-hour ids", RelayConsumption.CONSUMED,
        deliver(record(command("sync_trusted", 0).put("trusted_csv", "friend_one"))))

    // ---------------- key_rotated ----------------
    @Test fun aChildOfflineForDaysStillFollowsTheKeyRotation() {
        QrPairing.pairChild(ctx, parent)
        for (age in listOf(10 * day - 60_000L, 2 * day)) {
            val next = UUID.randomUUID().toString()
            assertEquals("key_rotated ${age / 3_600_000L} h old", RelayConsumption.CONSUMED, deliver(record(rotation(age, next))))
            assertEquals("the child follows the parent to the new token", next, token)
        }
        val current = token
        assertEquals("older than the outbox and the relay can hold it", RelayConsumption.REJECTED, deliver(record(rotation(10 * day + 60_000L))))
        assertEquals(current, token)
    }

    @Test fun aKeyRotationIsNeverAcceptedTwice() {
        QrPairing.pairChild(ctx, parent)
        val old = token; val next = UUID.randomUUID().toString(); val cmd = rotation(3 * day, next); val rotate = record(cmd)
        assertEquals(RelayConsumption.CONSUMED, deliver(rotate)); assertEquals(next, token)
        laterCommand()
        // The same record again: under the old room's key (no longer this child's) or re-encrypted for the new room (signed for the old scope).
        assertEquals(RelayConsumption.REJECTED, deliver(rotate, old))
        assertEquals(RelayConsumption.REJECTED, deliver(rotate, next))
        assertEquals(next, token)
        assertTrue("its id is kept past 24 hours", prefs.getString("control_state_json", "")!!.contains(cmd.getString("command_id")))
    }

    // ---------------- a later pairing ----------------
    @Test fun oldRecordsOfThePreviousFamilyAreRefusedAfterANewPairing() {
        QrPairing.pairChild(ctx, parent); val old = token
        val rotate = record(rotation(2 * day))
        // Paired again by QR with the same parent identity: a new family token, so a new room and a new scope.
        QrPairing.pairChild(ctx, parent, password = QrPairing.PASSWORD)
        val fresh = token; assertNotEquals(old, fresh)
        for (r in listOf(rotate)) {
            assertEquals(RelayConsumption.REJECTED, deliver(r, old))
            assertEquals(RelayConsumption.REJECTED, deliver(r, fresh))
        }
        assertEquals("child", role); assertEquals(fresh, token)
    }
}
