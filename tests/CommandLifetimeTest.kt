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
 * key_rotated and remote_reset_child must reach a child that was offline for days, as long as the parent's outbox and the relay
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
    private fun reset(age: Long) = command("remote_reset_child", age)
    /** The record as the parent queues it (signed, relay_control wrapper). */
    private fun record(cmd: JSONObject) = Pairing.commandRecord(FamilySecurity.signCommand(cmd, parent.privateKey), cmd.getString("command_id"))
    /** The relay hands [record] over under a fresh relay id, encrypted with [under]. */
    private fun deliver(record: JSONObject, under: String = token) = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message")
        .put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(under, record.toString())).toString(), "child", null)
    private fun laterCommand() = assertEquals("a later command prunes the 24-hour ids", RelayConsumption.CONSUMED,
        deliver(record(command("sync_trusted", 0).put("trusted_csv", "friend_one"))))

    @Test fun aCommandAheadOfTheChildsClockWaitsAtTheRelay() {
        QrPairing.pairChild(ctx, parent)
        val early = command("sync_trusted", -10 * 60_000L).put("trusted_csv", "friend_one")
        assertEquals("not consumed: redelivered once the clock is right", RelayConsumption.CLOCK_BEHIND, deliver(record(early)))
        assertFalse("not marked as taken", (prefs.getString("control_state_json", "") ?: "").contains(early.getString("command_id")))
        assertEquals("more than a day ahead stays refused", RelayConsumption.REJECTED,
            deliver(record(command("sync_trusted", -2 * day).put("trusted_csv", "friend_one"))))
        val forged = FamilySecurity.signCommand(command("sync_trusted", -10 * 60_000L).put("trusted_csv", "x"), FamilySecurity.newSigningIdentity().privateKey)
        assertEquals("an early command needs the parent's signature too", RelayConsumption.REJECTED,
            deliver(Pairing.commandRecord(forged, JSONObject(forged.getString("payload")).getString("command_id"))))
        laterCommand()
    }

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

    // ---------------- remote_reset_child ----------------
    @Test fun aChildOfflineForDaysStillReceivesTheRelease() {
        QrPairing.pairChild(ctx, parent)
        assertEquals(RelayConsumption.CONSUMED, deliver(record(reset(10 * day - 60_000L))))
        assertEquals("the late child is released", "unconfigured", role)
    }

    @Test fun aStaleReleaseIsRefused() {
        QrPairing.pairChild(ctx, parent)
        assertEquals(RelayConsumption.REJECTED, deliver(record(reset(10 * day + 60_000L))))
        assertEquals("child", role)
    }

    /** The relay re-delivers a record (same id and bytes) until it expires: after a re-pairing into the same room it must not act again. */
    @Test fun aReleaseIsNotReplayedAfterTheSameFamilyPairsTheChildAgain() {
        QrPairing.pairChild(ctx, parent); val room = token
        val release = record(reset(3 * day))
        assertEquals(RelayConsumption.CONSUMED, deliver(release)); assertEquals("unconfigured", role)
        assertTrue(GuardEngine.applyPairing(ctx, FamilySecurity.Invite(room, FamilySecurity.passwordVerifier(QrPairing.PASSWORD), parent.publicKey)))
        assertEquals(room, token); assertEquals("child", role)
        laterCommand()
        assertEquals("consumed without effect", RelayConsumption.CONSUMED, deliver(release))
        assertEquals("the replayed release does not reset the re-paired child", "child", role)
    }

    // ---------------- a later pairing ----------------
    @Test fun oldRecordsOfThePreviousFamilyAreRefusedAfterANewPairing() {
        QrPairing.pairChild(ctx, parent); val old = token
        val release = record(reset(2 * day)); val rotate = record(rotation(2 * day))
        // Paired again by QR with the same parent identity: a new family token, so a new room and a new scope.
        QrPairing.pairChild(ctx, parent, password = QrPairing.PASSWORD)
        val fresh = token; assertNotEquals(old, fresh)
        for (r in listOf(release, rotate)) {
            assertEquals(RelayConsumption.REJECTED, deliver(r, old))
            assertEquals(RelayConsumption.REJECTED, deliver(r, fresh))
        }
        assertEquals("child", role); assertEquals(fresh, token)
    }

    /** A reset the real parent queued for family A reaches the child only after the same parent and child paired again (token B). */
    @Test fun aResetQueuedForTheOldFamilyDoesNotTouchTheNewPairing() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        val invite = QrPairing.legacyInvite(ctx); val familyA = invite.token; val scopeA = GuardEngine.getChannelTopicForToken(familyA)
        val identity = FamilySecurity.loadOrCreateParentIdentity(ctx, null, invite.parentPublicKey)
        GuardEngine.rotateKeyAndNotifyChild(ctx, true)
        val queued = FamilyEvidence(ctx).use { it.due(scopeA, Long.MAX_VALUE, "parent") }.map { it.wire }
        assertTrue("the reset is queued for family A", queued.isNotEmpty())
        // This phone now plays the child: a child of family A, then paired again by QR with the same parent (new token B).
        GuardEngine.setRole(ctx, "unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx, invite)); assertEquals(familyA, token)
        QrPairing.pairChild(ctx, identity, password = QrPairing.PASSWORD)
        val familyB = token; assertNotEquals(familyA, familyB)
        for (wire in queued) {
            val frame = GuardEngine.decryptPayload(familyA, wire)!!
            assertEquals("remote_reset_child", JSONObject(frame.getJSONObject("envelope").getString("payload")).getString("type"))
            assertEquals("under A's key: unreadable here", RelayConsumption.REJECTED, GuardEngine.processIncomingRelayLine(ctx,
                JSONObject().put("event", "message").put("id", UUID.randomUUID().toString()).put("message", wire).toString(), "child", null))
            assertEquals("re-encrypted for B's room", RelayConsumption.REJECTED, deliver(frame, familyB))
            // The check that refuses it: the parent's signature is good, the signed scope is A's room, not B's.
            val now = System.currentTimeMillis()
            assertNotNull(FamilySecurity.readCommand(frame.getJSONObject("envelope"), identity.publicKey, scopeA, now))
            assertNull(FamilySecurity.readCommand(frame.getJSONObject("envelope"), identity.publicKey, GuardEngine.getChannelTopicForToken(familyB), now))
        }
        assertEquals("the new pairing is untouched", "child", role); assertEquals(familyB, token)
        assertTrue(GuardEngine.hasSecureParentCommands(ctx))
    }

    // ---------------- ordering over days, and a wrong child clock ----------------
    /** A late, older key_rotated after a newer one: refused, and REJECTED (not RETRY) so the relay connection consumes it. */
    @Test fun aLateOlderKeyRotationIsRefusedAndConsumed() {
        QrPairing.pairChild(ctx, parent)
        val b = UUID.randomUUID().toString()
        assertEquals(RelayConsumption.CONSUMED, deliver(record(rotation(day, b)))); assertEquals(b, token)
        val older = record(rotation(2 * day))   // signed for the room the child is in now, but older than the rotation it followed
        assertEquals(RelayConsumption.REJECTED, deliver(older))
        assertEquals(b, token)
        assertEquals("again: the same answer, never RETRY", RelayConsumption.REJECTED, deliver(older))
    }

    @Test fun aChildClockOutsideTheWindowRefusesTheCommand() {
        QrPairing.pairChild(ctx, parent); val current = token
        assertEquals("child clock 10 days and a minute ahead", RelayConsumption.REJECTED, deliver(record(rotation(10 * day + 60_000L))))
        assertEquals("child clock 3 minutes behind", RelayConsumption.CLOCK_BEHIND, deliver(record(reset(-3 * 60_000L))))
        assertEquals(current, token); assertEquals("child", role)
    }

    // ---------------- the child's queued records after a rotation ----------------
    /** The child's waiting records move to the new token's room with the rotation and must be sent at once, not at the next trigger. */
    @Test fun theChildSendsItsMovedRecordsRightAfterTheRotation() {
        val workers = java.util.concurrent.LinkedBlockingQueue<Long>()
        GuardEngine.beforeDeliveryWorkerEndsForTest = { workers.add(System.currentTimeMillis()) }
        try {
            QrPairing.pairChild(ctx, parent)
            GuardEngine.sendAlertFromChild(ctx, "test_alert", "測試", "test", bypassDedupe = true)
            while (workers.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null) { }   // the pairing's own deliveries have ended
            val oldScope = GuardEngine.getChannelTopic(ctx)
            assertTrue("records wait (no relay in tests)", FamilyEvidence(ctx).use { it.pendingCount(oldScope) } > 0)
            val next = UUID.randomUUID().toString()
            assertEquals(RelayConsumption.CONSUMED, deliver(record(rotation(60_000L, next))))
            val newScope = GuardEngine.getChannelTopicForToken(next)
            assertEquals(0, FamilyEvidence(ctx).use { it.pendingCount(oldScope) })
            assertTrue("moved to the new room", FamilyEvidence(ctx).use { it.pendingCount(newScope) } > 0)
            assertNotNull("a delivery ran for the moved records without another trigger", workers.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally { GuardEngine.beforeDeliveryWorkerEndsForTest = null }
    }
}
