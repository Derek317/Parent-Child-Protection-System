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
import app.family.igguard.ChildPairingState as CS

/**
 * "Legacy is forbidden" is a sticky fact, not a consequence of UI marks. Parent: once a family token came from a QR pairing
 * (FAMILY_QR_ONLY), an unpinned parent refuses every child record. Child: a QR pairing never sends the unsigned 5.2 format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyForbiddenTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val now = System.currentTimeMillis()
    private val token get() = GuardEngine.getOrCreateFamilyToken(ctx)

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
    }
    private fun parentPhone() { GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD) }
    private fun heartbeat() = JSONObject().put("type", "alert").put("event_id", UUID.randomUUID().toString()).put("capture_source", "app_or_visible_screen")
        .put("reason", "heartbeat_ok").put("sender", "子女手機定時回報").put("text", "裝置 運作中").put("ts_ms", System.currentTimeMillis()).put("time", "x")
        .put("trusted_csv", "").put("child_self_handle", "")
    private fun deliverUnsigned(): RelayConsumption = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message")
        .put("id", UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(token, heartbeat().toString())).toString(), "parent", null)
    private fun assertUnsignedRefused() {
        assertNull(GuardEngine.pinnedChildKey(ctx)); assertTrue(GuardEngine.familyQrOnly(ctx))
        assertEquals(RelayConsumption.REJECTED, deliverUnsigned())
        assertFalse(GuardEngine.hasChildHeartbeat(ctx)); assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
    }

    /** A QR pairing through the real parent link, accepted (K pinned, T1 stored, the sticky fact written with T1). */
    private fun acceptQrPairing(): ChildPairing {
        val rooms = FakeRooms(); val link = ParentPairingLink(ctx, {}, rooms.opener, { now })
        link.begin(); rooms.rooms[0].ready()
        val child = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)
        rooms.rooms[0].deliver(child.start(link.qrText!!, "Pixel 7", now + 1_000)!!.hello)
        assertTrue(child.onReply(rooms.rooms[0].records().single(), now + 2_000))
        assertTrue(link.enterDigits((child.state as CS.ShowingDigits).digits))
        child.confirm(now + 3_000); assertTrue(link.accept())
        assertNotNull(GuardEngine.pinnedChildKey(ctx)); assertTrue(GuardEngine.familyQrOnly(ctx))
        return child
    }

    @Test fun flagRefusesUnsignedAfterTheTenMinuteRollback() {
        parentPhone(); acceptQrPairing()
        assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, now + Pairing.CONFIRM_WINDOW_MS))
        assertUnsignedRefused()
    }

    @Test fun flagRefusesUnsignedAfterARemoteReset() {
        parentPhone(); acceptQrPairing()
        val scope = GuardEngine.getChannelTopic(ctx)
        GuardEngine.rotateKeyAndNotifyChild(ctx, true)
        for (frame in FamilyEvidence(ctx).use { it.due(scope, Long.MAX_VALUE, "parent") }) GuardEngine.recordRelayAcceptance(ctx, scope, frame)
        assertTrue(GuardEngine.childRemotelyReset(ctx))
        assertUnsignedRefused()
    }

    @Test fun flagRefusesUnsignedAfterTheForcedCancel() {
        parentPhone(); acceptQrPairing()
        assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, now, force = true))
        assertUnsignedRefused()
    }

    @Test fun flagSurvivesARotationAndRefusesUnsignedOnceUnpinnedByAnyPath() {
        parentPhone(); acceptQrPairing()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString(), preserveFamilyHistory = true)
        assertTrue("rotation keeps the fact", GuardEngine.familyQrOnly(ctx))
        prefs.edit().remove("pinned_child_key").remove("child_pairing_pending_ms").commit() // any unpinning path, without a mark
        assertUnsignedRefused()
    }

    @Test fun flagRefusesUnsignedEvenWithBothUiMarksCleared() {
        parentPhone(); acceptQrPairing()
        assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, now + Pairing.CONFIRM_WINDOW_MS))
        prefs.edit().remove("child_pairing_failed").remove("child_remote_reset").remove("child_pairing_cancelled").commit()
        assertEquals(GuardEngine.ChildTrust.NO_CHILD, GuardEngine.childTrust(ctx))
        assertUnsignedRefused()
    }

    @Test fun a52PairingWithoutTheFlagIsStillLegacy() {
        parentPhone(); token
        assertFalse(GuardEngine.familyQrOnly(ctx)); assertNull(GuardEngine.pinnedChildKey(ctx))
        assertEquals(RelayConsumption.CONSUMED, deliverUnsigned())
        assertTrue(GuardEngine.hasChildHeartbeat(ctx)); assertEquals(GuardEngine.ChildTrust.NOT_VERIFIED, GuardEngine.childTrust(ctx))
    }

    @Test fun aNewFamilyOrALeftParentRoleSetsOrClearsTheFlagExplicitly() {
        parentPhone(); acceptQrPairing()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString(), qrOnly = true)   // the parent setup page: a family made by this version
        assertTrue(GuardEngine.familyQrOnly(ctx)); assertNull(GuardEngine.pinnedChildKey(ctx))
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString())                // a new family that is not QR-only
        assertFalse(GuardEngine.familyQrOnly(ctx))
        acceptQrPairing()
        assertTrue(GuardEngine.leaveParentRole(ctx)); assertFalse(GuardEngine.familyQrOnly(ctx))
    }

    @Test fun leavingTheParentRoleRemovesThePinnedChildKey() {
        parentPhone(); acceptQrPairing()
        assertNotNull(prefs.getString("pinned_child_key", null))
        assertTrue(GuardEngine.leaveParentRole(ctx))
        for (key in listOf("pinned_child_key", "child_pairing_pending_ms", "child_record_state_json")) assertFalse(key, prefs.contains(key))
    }

    // ---------------- child side ----------------
    private fun queuedChildFrames() = FamilyEvidence(ctx).use { it.due(GuardEngine.getChannelTopicForToken(token), Long.MAX_VALUE) }
    private fun childSendsEverything() {
        GuardEngine.sendAlertFromChild(ctx, "test_alert", "測試", "test", bypassDedupe = true)
        GuardEngine.updateAutoDetectedChildHandle(ctx, "kid_handle_${UUID.randomUUID().toString().take(6)}")
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx, force = true)
    }

    @Test fun aQrPairedChildWithoutAUsableKeySendsNothing() {
        val r = QrPairing.pairChild(ctx)
        assertTrue(GuardEngine.childQrPaired(ctx))
        for (lose in listOf<() -> Unit>({ TestKeyStore.privateKeys.remove(r.childKeyAlias) }, { prefs.edit().remove("child_signing_alias").commit() })) {
            lose()
            val before = queuedChildFrames().map { it.id }.toSet()
            childSendsEverything()
            val added = queuedChildFrames().filter { it.id !in before }
            assertTrue("nothing leaves, signed or unsigned: ${added.map { it.id }}", added.isEmpty())
        }
    }

    @Test fun a52PairedChildStillSendsThe52Format() {
        parentPhone(); val invite = QrPairing.legacyInvite(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx, invite)); assertFalse(GuardEngine.childQrPaired(ctx))
        childSendsEverything()
        val records = queuedChildFrames().map { GuardEngine.decryptPayload(token, it.wire)!! }
        assertTrue(records.isNotEmpty()); assertTrue(records.none { it.has("child_signed") })
    }
}
