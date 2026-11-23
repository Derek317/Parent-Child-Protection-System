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

    @Test fun resetAndRoleChangeDeleteTheChildKey() {
        val r = QrPairing.pairChild(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        assertFalse(TestKeyStore.privateKeys.containsKey(r.childKeyAlias)); assertNull(prefs.getString("child_signing_alias", null))
    }
}
