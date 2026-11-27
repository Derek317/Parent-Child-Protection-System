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
import app.family.igguard.ParentPairingState as PS
import app.family.igguard.ChildPairingState as CS

/**
 * The QR pairing wired into GuardEngine (design m2 rev. 3, 2.5). One app context per test: each test drives ONE side with the real
 * engine (ParentPairingLink / ChildPairingLink, GuardEngine storage) and the other side with the bare state machine of Pairing.kt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QrPairingTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val now = System.currentTimeMillis()

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        TestKeyStore.failEcGeneration = false; TestKeyStore.failEcSigning = false
        GuardEngine.cpChildKeyRefused = false
    }
    private fun parentPhone() { GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD) }
    private fun childMachine() = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)

    /** Parent engine up to the typed digits, against a bare child machine. */
    private class ParentRun(val link: ParentPairingLink, val rooms: FakeRooms, val child: ChildPairing)
    private fun parentUpToDigits(clock: () -> Long = { now }): ParentRun {
        val rooms = FakeRooms()
        val link = ParentPairingLink(ctx, {}, rooms.opener, clock)
        link.begin()
        assertNull("no QR before the relay proved it supports child keys", link.qrText)
        rooms.rooms[0].ready()
        val qr = link.qrText!!
        val child = childMachine()
        val start = child.start(qr, "Pixel 7", now + 1_000)!!
        // The rendezvous room: derived from S and P, opened with a v1 parent proof.
        assertEquals(start.rendezvous.room, FamilyRelay.room(GuardEngine.relayPublicKey(ctx), start.rendezvous.scope))
        assertEquals(1, rooms.rooms[0].authorization().getInt("v")); assertEquals(start.rendezvous.room, rooms.rooms[0].authorization().getString("room"))
        rooms.rooms[0].deliver(start.hello)
        assertTrue(child.onReply(rooms.rooms[0].records().single(), now + 2_000))
        assertTrue(link.machine.state is PS.EnterDigits)
        return ParentRun(link, rooms, child)
    }
    private fun nothingPinned(oldToken: String) {
        assertNull(GuardEngine.pinnedChildKey(ctx)); assertFalse(GuardEngine.childPairingPending(ctx))
        assertEquals(oldToken, GuardEngine.getOrCreateFamilyToken(ctx))
    }

    /** The old child's capture confirmation does not carry over to a new QR pairing; the parent's own policy stays. */
    @Test fun aNewQrPairingForgetsTheOldChildsCaptureConfirmation() {
        parentPhone()
        prefs.edit().putString("capture_policy_id", "policy-1").putString("capture_child_ack_id", "policy-1").commit()
        assertTrue(GuardEngine.parentFullCaptureConfirmed(ctx))
        val run = parentUpToDigits()
        assertTrue(run.link.enterDigits((run.child.state as CS.ShowingDigits).digits))
        run.child.confirm(now + 3_000)!!
        assertTrue(run.link.accept())
        assertFalse("two-way capture is not confirmed for the new child", GuardEngine.parentFullCaptureConfirmed(ctx))
        assertEquals("policy-1", GuardEngine.pendingCapturePolicy(ctx))
    }

    @Test fun parentEnginePinsTheChildKeyReplacesTheTokenAndQueuesM3InTheNewRoom() {
        parentPhone(); val oldToken = GuardEngine.getOrCreateFamilyToken(ctx)
        val run = parentUpToDigits()
        val digits = (run.child.state as CS.ShowingDigits).digits
        assertTrue(run.link.enterDigits(digits))
        val family = run.child.confirm(now + 3_000)!!
        assertTrue(run.link.accept())
        val k = (TestKeyStore.privateKeys.keys.single { it.startsWith("ig_guard.child_signing") }).let { FamilySecurity.loadChildSigningKey(ctx, it).publicKey }
        assertEquals(FamilySecurity.canonicalKeyText(k), GuardEngine.pinnedChildKey(ctx))
        assertEquals(family.token, GuardEngine.getOrCreateFamilyToken(ctx)); assertNotEquals(oldToken, GuardEngine.getOrCreateFamilyToken(ctx))
        assertTrue(GuardEngine.childPairingPending(ctx))
        assertTrue("the rendezvous socket is closed after accept", run.rooms.rooms[0].closed)
        // M3 waits in the new room's parent outbox; the family connection registers that room with v2 (K) before it can publish.
        val queued = FamilyEvidence(ctx).use { it.due(family.scope, Long.MAX_VALUE, "parent") }.single()
        val m3 = GuardEngine.decryptPayload(family.token, queued.wire)!!
        val auth = JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, family.token).removePrefix("IGGuard "))))
        assertEquals(2, auth.getInt("v")); assertEquals(family.room, auth.getString("room"))
        assertEquals(k.trimEnd('='), auth.getString("child_key"))
        // The child machine accepts that M3: both phones agree.
        val done = run.child.onComplete(m3, now + 4_000)!!
        assertEquals(family.token, done.token); assertTrue(FamilySecurity.verifiesPassword(QrPairing.PASSWORD, done.passwordVerifier))
        // The old room keeps v1: a v1 parent never removes the relay's child lock.
        assertEquals(1, JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, oldToken).removePrefix("IGGuard ")))).getInt("v"))
    }

    private fun routes(): List<*> = GuardEngine::class.java.getDeclaredMethod("relayRouteTokens", android.content.Context::class.java)
        .apply { isAccessible = true }.invoke(GuardEngine, ctx) as List<*>
    @Test fun replacingAPairedChildReleasesItThroughItsOwnRoom() {
        parentPhone()
        val first = parentUpToDigits()
        assertTrue(first.link.enterDigits((first.child.state as CS.ShowingDigits).digits)); first.child.confirm(now + 3_000)!!
        assertTrue(first.link.accept())
        prefs.edit().remove("child_pairing_pending_ms").commit()
        assertEquals(GuardEngine.ChildTrust.VERIFIED, GuardEngine.childTrust(ctx))
        val oldToken = GuardEngine.getOrCreateFamilyToken(ctx); val oldScope = GuardEngine.getChannelTopicForToken(oldToken)
        FamilyEvidence(ctx).use { it.discardPending(oldScope, "parent") }
        val second = parentUpToDigits()
        assertTrue(second.link.enterDigits((second.child.state as CS.ShowingDigits).digits)); val family = second.child.confirm(now + 3_000)!!
        assertTrue("a release never holds up the new pairing", second.link.accept())
        val reset = FamilyEvidence(ctx).use { it.due(oldScope, Long.MAX_VALUE, "parent") }.single()
        val record = GuardEngine.decryptPayload(oldToken, reset.wire)!!
        assertNotNull("a reset signed by the parent for the old room",
            Pairing.readCommandRecord(record, "remote_reset_child", GuardEngine.relayPublicKey(ctx), oldScope, System.currentTimeMillis()))
        assertTrue("the new room never gets it", FamilyEvidence(ctx).use { it.due(family.scope, Long.MAX_VALUE, "parent") }
            .none { GuardEngine.decryptPayload(family.token, it.wire)?.optString("event_id") == record.optString("event_id") })
        assertEquals(listOf(family.token, oldToken), routes())
        assertEquals("the old room keeps v1", 1, JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, oldToken).removePrefix("IGGuard ")))).getInt("v"))
        GuardEngine.recordRelayAcceptance(ctx, oldScope, reset)
        assertEquals("once the relay holds it, the old room is no longer a route", listOf(family.token), routes())
        assertEquals(GuardEngine.ChildTrust.PAIRING, GuardEngine.childTrust(ctx))
        GuardEngine::class.java.getDeclaredMethod("finishDeliveryCallbacks", android.content.Context::class.java).apply { isAccessible = true }.invoke(GuardEngine, ctx)
        assertFalse("a release still queued is never logged as failed", GuardEngine.getCheckpointLogs(ctx).contains("傳送失敗或配對已變更"))
    }

    @Test fun nothingIsPinnedOnCancelWrongDigitsExpiryOrASecondPhone() {
        parentPhone(); val oldToken = GuardEngine.getOrCreateFamilyToken(ctx)
        // cancel
        parentUpToDigits().apply { link.cancel(); assertFalse(link.accept()); assertTrue(rooms.rooms[0].closed) }
        nothingPinned(oldToken)
        // three wrong entries
        parentUpToDigits().apply {
            val right = (child.state as CS.ShowingDigits).digits
            val wrong = listOf("000000", "000001", "000002").filter { it != right }.take(3)
            for (w in wrong) assertFalse(link.enterDigits(w))
            assertEquals(PS.Cancelled(Pairing.CancelReason.WRONG_DIGITS), link.machine.state)
            assertFalse(link.enterDigits(right)); assertFalse(link.accept())
        }
        nothingPinned(oldToken)
        // QR expiry: the QR leaves the screen
        var t = now
        val rooms = FakeRooms(); val link = ParentPairingLink(ctx, {}, rooms.opener, { t })
        link.begin(); rooms.rooms[0].ready(); assertNotNull(link.qrText)
        t = now + PairingCrypto.QR_LIFETIME_MS; link.tick()
        assertNull(link.qrText); assertEquals(PS.Cancelled(Pairing.CancelReason.EXPIRED), link.machine.state); assertTrue(rooms.rooms[0].closed)
        nothingPinned(oldToken)
        // a second phone answers the same QR (a photo of it)
        val second = FakeRooms(); val p = ParentPairingLink(ctx, {}, second.opener, { now })
        p.begin(); second.rooms[0].ready(); val qr = p.qrText!!
        second.rooms[0].deliver(childMachine().start(qr, "Pixel 7", now + 1_000)!!.hello)
        second.rooms[0].deliver(childMachine().start(qr, "Attacker", now + 1_100)!!.hello)
        assertEquals(PS.Cancelled(Pairing.CancelReason.SECOND_PHONE), p.machine.state); assertFalse(p.accept())
        nothingPinned(oldToken)
    }

    @Test fun aRelayWithoutChildProofGetsNoQr() {
        parentPhone()
        val rooms = FakeRooms(); val link = ParentPairingLink(ctx, {}, rooms.opener, { now })
        link.begin(); rooms.rooms[0].ready(childProof = false)
        assertNull(link.qrText); assertEquals(ParentPairingLink.Relay.TOO_OLD, link.relay)
        assertTrue(link.machine.state is PS.Cancelled); assertTrue(rooms.rooms[0].closed)
    }

    @Test fun anAcceptedPairingWithNoVerifiedRecordFor10MinutesIsRolledBack() {
        parentPhone()
        val run = parentUpToDigits()
        assertTrue(run.link.enterDigits((run.child.state as CS.ShowingDigits).digits))
        val family = run.child.confirm(now + 3_000)!!
        assertTrue(run.link.accept())
        val acceptedAt = now
        assertFalse(GuardEngine.expireUnconfirmedChildPairing(ctx, acceptedAt + Pairing.CONFIRM_WINDOW_MS - 1))
        assertNotNull(GuardEngine.pinnedChildKey(ctx))
        assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, acceptedAt + Pairing.CONFIRM_WINDOW_MS))
        assertNull("nothing stays pinned", GuardEngine.pinnedChildKey(ctx))
        assertFalse(GuardEngine.childPairingPending(ctx)); assertTrue("the overview asks to pair again", GuardEngine.childPairingFailed(ctx))
        assertTrue("M3 is not sent any more", FamilyEvidence(ctx).use { it.due(family.scope, Long.MAX_VALUE, "parent") }.isEmpty())
        assertEquals(1, JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, family.token).removePrefix("IGGuard ")))).getInt("v"))
    }

    /** After a rollback this parent is paired with no child: the 5.2 pairing ended at accept and the QR one never completed. */
    @Test fun aRolledBackPairingLeavesThisPhoneUnpairedAndTakesNoRecords() {
        parentPhone(); val oldToken = GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.recordChildHeartbeatOnParent(ctx, "裝置 Pixel 運作中", now - 60_000)
        assertEquals(GuardEngine.ChildTrust.NOT_VERIFIED, GuardEngine.childTrust(ctx))
        val run = parentUpToDigits()
        assertTrue(run.link.enterDigits((run.child.state as CS.ShowingDigits).digits))
        val family = run.child.confirm(now + 3_000)!!
        assertTrue(run.link.accept())
        assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, now + Pairing.CONFIRM_WINDOW_MS))
        // What remains: the new token T1 (the 5.2 token is gone, nothing routes to it), no pin, no last report, the failed mark.
        assertEquals(family.token, GuardEngine.getOrCreateFamilyToken(ctx)); assertNotEquals(oldToken, family.token)
        assertNull(GuardEngine.pinnedChildKey(ctx)); assertFalse(GuardEngine.hasChildHeartbeat(ctx))
        assertEquals(GuardEngine.ChildTrust.PAIRING_FAILED, GuardEngine.childTrust(ctx))
        val alerts = GuardEngine.getParentAlerts(ctx).size
        fun heartbeat() = JSONObject().put("type", "alert").put("event_id", java.util.UUID.randomUUID().toString()).put("capture_source", "app_or_visible_screen")
            .put("reason", "heartbeat_ok").put("sender", "子女手機定時回報").put("text", "裝置 運作中").put("ts_ms", System.currentTimeMillis()).put("time", "x")
            .put("trusted_csv", "").put("child_self_handle", "")
        fun deliver(token: String, frame: JSONObject) = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message")
            .put("id", java.util.UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(token, frame.toString())).toString(), "parent", null)
        assertEquals("an old-pairing (5.2) report", RelayConsumption.REJECTED, deliver(oldToken, heartbeat()))
        assertEquals("an unsigned report under T1 is not taken as Legacy", RelayConsumption.REJECTED, deliver(family.token, heartbeat()))
        val alias = TestKeyStore.privateKeys.keys.single { it.startsWith("ig_guard.child_signing") }
        val record = heartbeat()
        assertEquals("a late report signed by the unpinned K", RelayConsumption.REJECTED, deliver(family.token, ChildRecords.wrapForParent(record.toString(),
            record.getString("event_id"), GuardEngine.relayPublicKey(ctx), FamilySecurity.loadChildSigningKey(ctx, alias).publicKey, { FamilySecurity.signChildBytes(it, alias) }, true)))
        assertEquals(alerts, GuardEngine.getParentAlerts(ctx).size); assertFalse(GuardEngine.hasChildHeartbeat(ctx))
        assertEquals(GuardEngine.ChildTrust.PAIRING_FAILED, GuardEngine.childTrust(ctx))
    }

    @Test fun theParentsOwnCancelIsTheRollbackAtOnceAndALateVerifiedRecordIsRefused() {
        parentPhone()
        val run = parentUpToDigits()
        assertTrue(run.link.enterDigits((run.child.state as CS.ShowingDigits).digits))
        val family = run.child.confirm(now + 3_000)!!
        assertTrue(run.link.accept())
        val m3 = GuardEngine.decryptPayload(family.token, FamilyEvidence(ctx).use { it.due(family.scope, Long.MAX_VALUE, "parent") }.single().wire)!!
        assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, now + 1_000, force = true))
        assertNull(GuardEngine.pinnedChildKey(ctx)); assertFalse(GuardEngine.childPairingPending(ctx))
        assertTrue("M3 is dropped", FamilyEvidence(ctx).use { it.due(family.scope, Long.MAX_VALUE, "parent") }.isEmpty())
        assertEquals(GuardEngine.ChildTrust.PAIRING_FAILED, GuardEngine.childTrust(ctx)); assertTrue(GuardEngine.childPairingCancelled(ctx))
        assertFalse("nothing left to roll back", GuardEngine.expireUnconfirmedChildPairing(ctx, now + Pairing.CONFIRM_WINDOW_MS))
        // The cancelled child got M3 anyway and sends its first verified record: refused.
        val done = run.child.onComplete(m3, now + 4_000)!!
        val record = JSONObject().put("type", "alert").put("event_id", java.util.UUID.randomUUID().toString()).put("capture_source", "app_or_visible_screen")
            .put("reason", "pairing_verified").put("sender", "Pixel 7").put("text", "子女手機已用 QR 碼完成配對。").put("ts_ms", System.currentTimeMillis()).put("time", "x")
            .put("trusted_csv", "").put("child_self_handle", "")
        val frame = ChildRecords.wrapForParent(record.toString(), record.getString("event_id"), GuardEngine.relayPublicKey(ctx), done.childKey, { FamilySecurity.signChildBytes(it, done.childKeyAlias) }, true)
        val line = JSONObject().put("event", "message").put("id", java.util.UUID.randomUUID().toString()).put("message", GuardEngine.encryptPayload(family.token, frame.toString())).toString()
        assertEquals(RelayConsumption.REJECTED, GuardEngine.processIncomingRelayLine(ctx, line, "parent", null))
        assertNull(GuardEngine.pinnedChildKey(ctx)); assertFalse(GuardEngine.hasChildHeartbeat(ctx)); assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
        // A new QR pairing clears the cancel mark.
        val again = parentUpToDigits()
        assertTrue(again.link.enterDigits((again.child.state as CS.ShowingDigits).digits)); again.child.confirm(now + 3_000); assertTrue(again.link.accept())
        assertFalse(GuardEngine.childPairingCancelled(ctx)); assertEquals(GuardEngine.ChildTrust.PAIRING, GuardEngine.childTrust(ctx))
    }

    /** A crash after the first verified record was stored but before its commit: the resent record still completes the pairing. */
    @Test fun aResentFirstRecordAfterACrashBeforeTheCommitStillCompletesThePairing() {
        parentPhone()
        val run = parentUpToDigits()
        assertTrue(run.link.enterDigits((run.child.state as CS.ShowingDigits).digits))
        val family = run.child.confirm(now + 3_000)!!
        assertTrue(run.link.accept())
        val m3 = GuardEngine.decryptPayload(family.token, FamilyEvidence(ctx).use { it.due(family.scope, Long.MAX_VALUE, "parent") }.single().wire)!!
        val done = run.child.onComplete(m3, now + 4_000)!!
        val record = JSONObject().put("type", "alert").put("event_id", java.util.UUID.randomUUID().toString()).put("capture_source", "app_or_visible_screen")
            .put("reason", "pairing_verified").put("sender", "Pixel 7").put("text", "子女手機已用 QR 碼完成配對。").put("ts_ms", System.currentTimeMillis()).put("time", "x")
            .put("trusted_csv", "").put("child_self_handle", "")
        val frame = ChildRecords.wrapForParent(record.toString(), record.getString("event_id"), GuardEngine.relayPublicKey(ctx), done.childKey, { FamilySecurity.signChildBytes(it, done.childKeyAlias) }, true)
        fun deliver() = GuardEngine.processIncomingRelayLine(ctx, JSONObject().put("event", "message").put("id", java.util.UUID.randomUUID().toString())
            .put("message", GuardEngine.encryptPayload(family.token, frame.toString())).toString(), "parent", null)
        val pendingMs = prefs.getLong("child_pairing_pending_ms", 0L); val state = prefs.getString("child_record_state_json", null)
        assertTrue(pendingMs > 0L)
        assertEquals(RelayConsumption.CONSUMED, deliver())
        // The crash: the record is in the database, the commit that follows it never happened.
        prefs.edit().putLong("child_pairing_pending_ms", pendingMs).apply { if (state == null) remove("child_record_state_json") else putString("child_record_state_json", state) }.commit()
        assertEquals(RelayConsumption.CONSUMED, deliver())
        assertFalse("the resent verified record completes the pairing", GuardEngine.childPairingPending(ctx))
        assertFalse(GuardEngine.expireUnconfirmedChildPairing(ctx, now + Pairing.CONFIRM_WINDOW_MS))
        assertNotNull("the child stays pinned", GuardEngine.pinnedChildKey(ctx))
    }

    @Test fun childEngineStoresAliasParentKeyTokenAndVerifier() {
        val identity = FamilySecurity.newSigningIdentity()
        val r = QrPairing.pairChild(ctx, identity)
        assertEquals("child", GuardEngine.getRole(ctx))
        assertEquals(r.childKeyAlias, GuardEngine.childKeyAlias(ctx))
        assertEquals(r.token, GuardEngine.getOrCreateFamilyToken(ctx))
        assertEquals(identity.publicKey, prefs.getString("parent_command_public_key", null))
        assertTrue(GuardEngine.verifyParentPassword(ctx, QrPairing.PASSWORD))
        // v2 child proof for the family room only.
        val auth = JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, r.token).removePrefix("IGGuard "))))
        assertEquals(2, auth.getInt("v")); assertEquals("child", auth.getString("role")); assertEquals(r.childKey.trimEnd('='), auth.getString("child_key"))
    }

    @Test fun childJoinUsesV1InTheRendezvousAndV2InTheFamilyRoomAndCancelStoresNothing() {
        val identity = FamilySecurity.newSigningIdentity(); val parent = QrPairing.bareParent(identity)
        val start = parent.start(identity.publicKey, now)
        val rooms = FakeRooms(); var stored = false
        val link = ChildPairingLink(ctx, {}, { stored = true }, rooms.opener, { now + 1_000 })
        assertTrue(link.join(start.qrText))
        assertEquals(1, rooms.rooms[0].authorization().getInt("v")); assertEquals(start.rendezvous.room, rooms.rooms[0].authorization().getString("room"))
        assertTrue("M1 waits for ready", rooms.rooms[0].published.isEmpty())
        rooms.rooms[0].ready()
        rooms.rooms[0].deliver(parent.onHello(rooms.rooms[0].records().single(), now + 2_000)!!)
        assertTrue(link.confirm())
        assertEquals(2, rooms.rooms[1].authorization().getInt("v")); assertTrue(rooms.rooms[0].closed)
        val alias = TestKeyStore.privateKeys.keys.single { it.startsWith("ig_guard.child_signing") }
        link.cancel()
        assertFalse(stored); assertFalse("the child key is deleted", TestKeyStore.privateKeys.containsKey(alias))
        assertEquals("unconfigured", GuardEngine.getRole(ctx)); assertNull(prefs.getString("family_uuid_token", null)); assertNull(GuardEngine.childKeyAlias(ctx))
        // A refused rendezvous room (401/410) also stops it and deletes the key.
        val again = FakeRooms(); val second = ChildPairingLink(ctx, {}, { stored = true }, again.opener, { now + 1_000 })
        assertTrue(second.join(QrPairing.bareParent(identity).start(identity.publicKey, now).qrText))
        again.rooms[0].route.onRefused(410); idle()
        assertTrue(second.refused); assertTrue(second.machine.state is CS.Cancelled)
        assertTrue(TestKeyStore.privateKeys.keys.none { it.startsWith("ig_guard.child_signing") })
    }

    /** "Pair again to verify" keeps the family history: parent commands can reach the new room before M3. */
    @Test fun aParentCommandAheadOfM3IsIgnoredAndOnlyABadM3Cancels() {
        val identity = FamilySecurity.newSigningIdentity(); val parent = QrPairing.bareParent(identity)
        val start = parent.start(identity.publicKey, now)
        val rooms = FakeRooms(); var result: ChildPairing.Result? = null
        val link = ChildPairingLink(ctx, {}, { result = it }, rooms.opener, { now + 1_000 })
        link.join(start.qrText); rooms.rooms[0].ready()
        rooms.rooms[0].deliver(parent.onHello(rooms.rooms[0].records().single(), now + 2_000)!!)
        assertTrue(parent.digitsEntered((link.machine.state as CS.ShowingDigits).digits)); assertTrue(link.confirm())
        val done = parent.accept(FamilySecurity.passwordVerifier(QrPairing.PASSWORD), now + 3_000)!!
        val id = java.util.UUID.randomUUID().toString()
        val command = Pairing.commandRecord(FamilySecurity.signCommand(JSONObject().put("type", "capture_policy").put("scope", done.family.scope)
            .put("command_id", id).put("ts_ms", now).put("schema_version", 2).put("mode", "full_visible_conversation").put("policy_id", id), identity.privateKey), id)
        assertFalse(Pairing.isPairingRecord(command)); assertTrue(Pairing.isPairingRecord(done.complete))
        rooms.rooms[1].deliver(command)
        assertEquals("not acted on, not a cancel", CS.AwaitingComplete, link.machine.state)
        assertEquals("nothing stored, no receipt sent", 0, rooms.rooms[1].published.size); assertNull(prefs.getString("capture_policy_id", null))
        rooms.rooms[1].deliver(done.complete)
        assertNotNull(result); assertEquals(CS.Paired, link.machine.state)
        // A pair_complete that fails the machine's checks still cancels.
        val again = QrPairing.bareParent(identity); val s2 = again.start(identity.publicKey, now)
        val r2 = FakeRooms(); val l2 = ChildPairingLink(ctx, {}, {}, r2.opener, { now + 1_000 })
        l2.join(s2.qrText); r2.rooms[0].ready()
        r2.rooms[0].deliver(again.onHello(r2.rooms[0].records().single(), now + 2_000)!!)
        assertTrue(again.digitsEntered((l2.machine.state as CS.ShowingDigits).digits)); assertTrue(l2.confirm())
        r2.rooms[1].deliver(done.complete) // M3 of the other pairing: wrong scope and key hash
        assertEquals(CS.Cancelled(Pairing.CancelReason.BAD_MESSAGE), l2.machine.state)
    }

    @Test fun aPairedChildNeedsTheParentPasswordToJoinByQrAndANewPairingDeletesTheOldKey() {
        // A 5.2 pairing in the store.
        parentPhone(); val invite = QrPairing.legacyInvite(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx, invite)); assertNull(GuardEngine.childKeyAlias(ctx))
        val identity = FamilySecurity.newSigningIdentity(); val parent = QrPairing.bareParent(identity)
        val start = parent.start(identity.publicKey, now)
        val rooms = FakeRooms(); var result: ChildPairing.Result? = null
        val link = ChildPairingLink(ctx, {}, { result = it }, rooms.opener, { now + 1_000 })
        link.join(start.qrText); rooms.rooms[0].ready()
        rooms.rooms[0].deliver(parent.onHello(rooms.rooms[0].records().single(), now + 2_000)!!)
        assertTrue(parent.digitsEntered((link.machine.state as CS.ShowingDigits).digits)); link.confirm()
        rooms.rooms[1].deliver(parent.accept(FamilySecurity.passwordVerifier("NewParent928!x"), now + 3_000)!!.complete)
        val r = result!!
        assertFalse("no password: refused", GuardEngine.completeChildPairing(ctx, r, null))
        assertEquals(invite.token, GuardEngine.getOrCreateFamilyToken(ctx))
        assertFalse("its key is deleted", TestKeyStore.privateKeys.containsKey(r.childKeyAlias))
        // With the password, through a fresh pairing.
        val second = QrPairing.pairChild(ctx, password = QrPairing.PASSWORD)
        assertEquals(second.childKeyAlias, GuardEngine.childKeyAlias(ctx))
        // A further QR pairing replaces K: the old alias and key are gone.
        val third = QrPairing.pairChild(ctx, password = QrPairing.PASSWORD)
        assertEquals(third.childKeyAlias, GuardEngine.childKeyAlias(ctx))
        assertFalse(TestKeyStore.privateKeys.containsKey(second.childKeyAlias))
    }

    /** Key rotation (rotateKeyAndNotifyChild) ends in setFamilyToken(new, preserveFamilyHistory = true) on both phones: the pin and
     *  K survive it, and the rotated room is registered and joined with v2 proofs carrying the same K. */
    @Test fun aRotatedFamilyKeepsTheChildKeyAndUsesV2InItsNewRoom() {
        val r = QrPairing.pairChild(ctx)
        val rotated = java.util.UUID.randomUUID().toString()
        GuardEngine.setFamilyToken(ctx, rotated, preserveFamilyHistory = true)
        assertEquals(r.childKeyAlias, GuardEngine.childKeyAlias(ctx))
        val child = JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, rotated).removePrefix("IGGuard "))))
        assertEquals(2, child.getInt("v")); assertEquals(r.childKey.trimEnd('='), child.getString("child_key"))
        prefs.edit().clear().commit()
        parentPhone()
        val run = parentUpToDigits()
        assertTrue(run.link.enterDigits((run.child.state as CS.ShowingDigits).digits)); run.child.confirm(now + 3_000); assertTrue(run.link.accept())
        val pinned = GuardEngine.pinnedChildKey(ctx)!!
        val next = java.util.UUID.randomUUID().toString()
        GuardEngine.setFamilyToken(ctx, next, preserveFamilyHistory = true)
        assertEquals(pinned, GuardEngine.pinnedChildKey(ctx))
        val parent = JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, next).removePrefix("IGGuard "))))
        assertEquals(2, parent.getInt("v")); assertEquals(pinned.trimEnd('='), parent.getString("child_key"))
    }

    @Test fun resetAndRoleChangeDeleteTheChildKey() {
        val r = QrPairing.pairChild(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        assertFalse(TestKeyStore.privateKeys.containsKey(r.childKeyAlias)); assertNull(prefs.getString("child_signing_alias", null))
    }
}
