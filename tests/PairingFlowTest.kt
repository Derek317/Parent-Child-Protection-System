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
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import app.family.igguard.Pairing.CancelReason
import app.family.igguard.ParentPairingState as PS
import app.family.igguard.ChildPairingState as CS

/** Both pairing state machines driven against each other through an in-memory relay that encrypts every record
 * with the app's own AES-GCM (GuardEngine.encryptPayload) under the room token. Child keys come from the test Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PairingFlowTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private val palette = listOf("🐼", "🦁", "🐬", "🦊", "🐨", "🐯", "🦄", "🐝", "🚀", "🌈", "🍀", "🌻", "🍎", "🍉", "⚽", "🎸")
    private val encoder = Base64.getUrlEncoder()
    private val parent = FamilySecurity.newSigningIdentity()
    private val verifier = FamilySecurity.passwordVerifier("ParentPassword928!")
    private val deleted = mutableListOf<String>()
    private val created = mutableListOf<FamilySecurity.ChildSigningKey>()
    /** Plaintext of every record that crossed the relay. */
    private val wire = mutableListOf<String>()

    @Before fun reset() {
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        TestKeyStore.failEcGeneration = false; TestKeyStore.failEcSigning = false
    }

    private fun parentMachine(signer: FamilySecurity.SigningIdentity = parent, ephemeral: (() -> KeyPair)? = null, secret: (() -> String)? = null) =
        ParentPairing({ FamilySecurity.signCommand(it, signer.privateKey) }, palette,
            newSecret = secret ?: PairingCrypto::newPairingSecret, newEphemeral = ephemeral ?: PairingCrypto::newEphemeralKeyPair)
    private fun childMachine(key: (() -> FamilySecurity.ChildSigningKey)? = null, ephemeral: (() -> KeyPair)? = null) =
        ChildPairing({ (key?.invoke() ?: FamilySecurity.createChildSigningKey(ctx)).also { created += it } },
            { deleted += it; FamilySecurity.deleteChildSigningKey(ctx, it) }, palette, newEphemeral = ephemeral ?: PairingCrypto::newEphemeralKeyPair)
    /** The relay: encrypt under the room token, decrypt on the other side (a GCM tag that verifies proves knowledge of the token). */
    private fun relay(record: JSONObject, token: String): JSONObject {
        val plain = record.toString(); wire += plain
        val received = GuardEngine.decryptPayload(token, GuardEngine.encryptPayload(token, plain))!!
        assertNull(GuardEngine.decryptPayload(PairingCrypto.newPairingSecret(), GuardEngine.encryptPayload(token, plain)))
        return received
    }
    private fun inner(m1: JSONObject) = JSONObject(m1.getString("child_signed"))
    private fun payload(record: JSONObject) = JSONObject(record.getJSONObject("envelope").getString("payload"))
    private fun digitsOf(c: ChildPairing) = (c.state as CS.ShowingDigits).digits
    private fun otherKey() = PairingCrypto.publicKeyText(PairingCrypto.newEphemeralKeyPair().public)

    /** Parent started, child scanned; returns (parent, child, start, M1 as received). */
    private fun scanned(): Triple<ParentPairing, ChildPairing, Pair<ParentPairing.Start, JSONObject>> {
        val p = parentMachine(); val c = childMachine()
        val start = p.start(parent.publicKey, now)
        val cs = c.start(start.qrText, "Pixel 7", now + 1_000)!!
        assertEquals(start.rendezvous, cs.rendezvous)
        return Triple(p, c, start to relay(cs.hello, start.rendezvous.token))
    }
    /** Up to M2 delivered to the child. */
    private fun replied(): Triple<ParentPairing, ChildPairing, Pair<ParentPairing.Start, JSONObject>> {
        val (p, c, sm) = scanned()
        val m2 = p.onHello(sm.second, now + 2_000)!!
        assertTrue(c.onReply(relay(m2, sm.first.rendezvous.token), now + 3_000))
        return Triple(p, c, sm.first to m2)
    }
    private fun assertParentCancelled(p: ParentPairing, reason: CancelReason) {
        assertEquals(PS.Cancelled(reason), p.state); assertFalse(p.holdsSecrets)
        assertFalse(p.digitsEntered("000000")); assertNull(p.accept(verifier, now + 5_000))
    }
    private fun assertChildCancelled(c: ChildPairing, reason: CancelReason) {
        assertEquals(CS.Cancelled(reason), c.state); assertFalse(c.holdsSecrets)
        assertEquals(created.map { it.alias }, deleted)
        assertTrue(created.none { TestKeyStore.privateKeys.containsKey(it.alias) })
    }
    /** An M1 built by hand: record fields as the child sends them, signed with a software key. */
    private fun hello(scope: String, k: String, ec: String, signer: FamilySecurity.SigningIdentity, ts: Long = now + 1_000, p: String = parent.publicKey,
                      model: Any = "Pixel 7", id: String = UUID.randomUUID().toString()): JSONObject {
        val r = JSONObject().put("type", "pair_hello").put("scope", scope).put("command_id", id).put("ts_ms", ts).put("k", k).put("ec", ec).put("model", model).toString()
        return JSONObject().put("type", "alert").put("event_id", "control:$id").put("child_signed", r)
            .put("child_sig", encoder.encodeToString(FamilySecurity.signBytes(FamilySecurity.childRecordSignedBytes(r, p, k), signer.privateKey)))
    }
    private fun command(type: String, scope: String, fields: Map<String, Any>, signer: FamilySecurity.SigningIdentity = parent, ts: Long = now + 2_000): JSONObject {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("type", type).put("scope", scope).put("command_id", id).put("ts_ms", ts)
        fields.forEach { (key, value) -> body.put(key, value) }
        return Pairing.commandRecord(FamilySecurity.signCommand(body, signer.privateKey), id)
    }

    @Test fun happyPathBothPhonesAgreeAndOnlyTheRightThingsAreSent() {
        val p = parentMachine(); val c = childMachine()
        val start = p.start(parent.publicKey, now)
        assertEquals(PS.ShowingQr(now + PairingCrypto.QR_LIFETIME_MS), p.state)
        val s = start.qrText.split('.')[1]
        assertEquals(Pairing.rendezvous(s, parent.publicKey), start.rendezvous)
        assertEquals(GuardEngine.getChannelTopicForToken(s), start.rendezvous.scope)
        assertEquals(FamilyRelay.room(parent.publicKey, start.rendezvous.scope), start.rendezvous.room)
        assertEquals(FamilyRelay.childCapability(s), start.rendezvous.childCapability)

        val cs = c.start(start.qrText, "Pixel 7", now + 1_000)!!
        assertEquals(CS.AwaitingReply, c.state)
        val m1 = relay(cs.hello, start.rendezvous.token)
        assertEquals(setOf("type", "event_id", "child_signed", "child_sig"), m1.keys().asSequence().toSet())
        val r1 = inner(m1)
        assertEquals(setOf("type", "scope", "command_id", "ts_ms", "k", "ec", "model"), r1.keys().asSequence().toSet())
        assertEquals("control:" + r1.getString("command_id"), m1.getString("event_id"))
        assertEquals(created.single().publicKey, r1.getString("k"))
        assertNotNull(FamilySecurity.readChildRecord(m1, created.single().publicKey, parent.publicKey, now + 2_000))

        val m2 = p.onHello(m1, now + 2_000)!!
        assertEquals(setOf("type", "event_id", "relay_control", "envelope"), m2.keys().asSequence().toSet())
        val r2 = payload(m2)
        assertEquals(setOf("type", "scope", "command_id", "ts_ms", "k_hash", "ec_hash", "ep"), r2.keys().asSequence().toSet())
        assertEquals(start.rendezvous.scope, r2.getString("scope"))
        assertTrue(c.onReply(relay(m2, start.rendezvous.token), now + 3_000))
        val shown = c.state as CS.ShowingDigits
        val parentState = p.state as PS.EnterDigits
        assertEquals(PS.EnterDigits(3, shown.pictures, "Pixel 7"), parentState)
        assertTrue(c.holdsSecrets && p.holdsSecrets)                           // T1 is held until the end, nothing else

        assertTrue(p.digitsEntered(shown.digits))
        assertEquals(PS.ReadyToAccept(shown.pictures, "Pixel 7"), p.state)
        val family = c.confirm(now + 4_000)!!
        assertEquals(CS.AwaitingComplete, c.state)
        val result = p.accept(verifier, now + 5_000)!!
        assertEquals(PS.Accepted, p.state); assertFalse(p.holdsSecrets)
        assertEquals(family, result.family)
        assertEquals(created.single().publicKey, result.childKey)
        assertEquals(shown.pictures, result.pictures)
        assertNotEquals(start.rendezvous.room, result.family.room)
        assertEquals(FamilyRelay.room(parent.publicKey, GuardEngine.getChannelTopicForToken(result.token)), result.family.room)
        val r3 = payload(result.complete)
        assertEquals(setOf("type", "scope", "command_id", "ts_ms", "new_pin", "k_hash"), r3.keys().asSequence().toSet())
        assertEquals(result.family.scope, r3.getString("scope"))

        val done = c.onComplete(relay(result.complete, result.family.token), now + 6_000)!!
        assertEquals(CS.Paired, c.state); assertFalse(c.holdsSecrets)
        assertEquals(ChildPairing.Result(result.token, parent.publicKey, verifier, created.single().alias, result.childKey), done)
        assertTrue(FamilySecurity.validToken(done.token))
        assertTrue(deleted.isEmpty())
        // T1 and S never occur in any message; nor do the digits.
        assertEquals(3, wire.size)
        for (text in wire) for (secret in listOf(s, result.token, shown.digits)) assertFalse(text.contains(secret))
        // toString never prints a secret either.
        for (o in listOf<Any>(start, start.rendezvous, result, done, family)) for (secret in listOf(s, result.token)) assertFalse(o.toString().contains(secret))
    }

    @Test fun secondPhoneBeforeTheDigitsCancels() {
        val (p, _, sm) = scanned()
        val m2 = p.onHello(sm.second, now + 2_000)!!
        assertSame(m2, p.onHello(JSONObject(sm.second.toString()), now + 2_100))        // byte-identical resend: same M2
        assertTrue(p.state is PS.EnterDigits)
        val second = childMachine().start(sm.first.qrText, "Other", now + 2_200)!!        // a photo of the QR
        assertNull(p.onHello(relay(second.hello, sm.first.rendezvous.token), now + 2_300))
        assertParentCancelled(p, CancelReason.SECOND_PHONE)
    }

    @Test fun secondDistinctM1AfterM2EvenWithTheSameKIsASecondPhone() {
        val p = parentMachine(); val c = childMachine()
        val start = p.start(parent.publicKey, now)
        val cs = c.start(start.qrText, "Pixel 7", now + 1_000)!!
        assertNotNull(p.onHello(relay(cs.hello, start.rendezvous.token), now + 2_000))
        // Same K, different Ec (a second scan reusing the key): distinct M1.
        val sameKey = created.single()
        val again = childMachine(key = { sameKey }).start(start.qrText, "Pixel 7", now + 2_500)!!
        assertNull(p.onHello(relay(again.hello, start.rendezvous.token), now + 3_000))
        assertParentCancelled(p, CancelReason.SECOND_PHONE)
    }

    @Test fun secondPhoneAfterAcceptableStateCancelsBeforeAccept() {
        val p = parentMachine(); val c = childMachine()
        val start = p.start(parent.publicKey, now)
        val cs = c.start(start.qrText, "Pixel 7", now + 1_000)!!
        val m2 = p.onHello(relay(cs.hello, start.rendezvous.token), now + 2_000)!!
        assertTrue(c.onReply(relay(m2, start.rendezvous.token), now + 3_000))
        assertTrue(p.digitsEntered(digitsOf(c)))
        val attacker = childMachine().start(start.qrText, "Attacker", now + 3_500)!!
        assertNull(p.onHello(relay(attacker.hello, start.rendezvous.token), now + 4_000))
        assertParentCancelled(p, CancelReason.SECOND_PHONE)
    }

    @Test fun attackerAnsweringFirstCannotBeAcceptedWithoutTheDigits() {
        val p = parentMachine()
        val start = p.start(parent.publicKey, now)
        val attacker = childMachine()
        val m2 = p.onHello(relay(attacker.start(start.qrText, "Attacker", now + 1_000)!!.hello, start.rendezvous.token), now + 2_000)!!
        assertTrue(attacker.onReply(relay(m2, start.rendezvous.token), now + 3_000))
        val real = digitsOf(attacker) // exists only on the attacker's phone
        assertNull(p.accept(verifier, now + 4_000))                           // accept before digits
        val guesses = (0..9).map { "%06d".format(it) }.filter { it != real }.take(3)
        assertFalse(p.digitsEntered(guesses[0])); assertEquals(2, (p.state as PS.EnterDigits).triesLeft)
        assertFalse(p.digitsEntered(guesses[1])); assertEquals(1, (p.state as PS.EnterDigits).triesLeft)
        assertNull(p.accept(verifier, now + 4_000))
        assertFalse(p.digitsEntered(guesses[2]))
        assertParentCancelled(p, CancelReason.WRONG_DIGITS)
        assertFalse(p.digitsEntered(real))                                     // too late
    }

    @Test fun badM1sAreRefusedAndCancel() {
        val child = FamilySecurity.newSigningIdentity(); val stranger = FamilySecurity.newSigningIdentity()
        val ec = otherKey()
        val cases = mutableListOf<Pair<String, (String) -> JSONObject>>(
            "K signed by another key" to { scope -> hello(scope, child.publicKey, ec, stranger) },
            "k differs from the signing key" to { scope -> hello(scope, stranger.publicKey, ec, child) },
            "substituted Ec" to { scope -> hello(scope, child.publicKey, ec, child).let { w -> w.put("child_signed", inner(w).put("ec", otherKey()).toString()) } },
            "earlier QR (other scope)" to { _ -> hello(GuardEngine.getChannelTopicForToken(PairingCrypto.newPairingSecret()), child.publicKey, ec, child) },
            "other family (P in prefix)" to { scope -> hello(scope, child.publicKey, ec, child, p = stranger.publicKey) },
            "future time" to { scope -> hello(scope, child.publicKey, ec, child, ts = now + 2_000 + Pairing.CLOCK_SKEW_MS + 1) },
            "before the QR existed" to { scope -> hello(scope, child.publicKey, ec, child, ts = now - Pairing.CLOCK_SKEW_MS - 1) },
            "unpadded K" to { scope -> hello(scope, child.publicKey.trimEnd('='), ec, child) },
            "unpadded Ec" to { scope -> hello(scope, child.publicKey, ec.trimEnd('='), child) },
            "invalid Ec" to { scope -> hello(scope, child.publicKey, "garbage", child) },
            "K is the parent key" to { scope -> hello(scope, parent.publicKey, ec, parent) },
            "no model" to { scope -> hello(scope, child.publicKey, ec, child, model = 5) },
            "model too long" to { scope -> hello(scope, child.publicKey, ec, child, model = "x".repeat(65)) },
            "wrapper id differs" to { scope -> hello(scope, child.publicKey, ec, child).put("event_id", "control:" + UUID.randomUUID()) },
            "wrapper type" to { scope -> hello(scope, child.publicKey, ec, child).put("type", "captured_message") },
            "a parent command instead" to { scope -> command("pair_reply", scope, mapOf("k_hash" to "0".repeat(64), "ec_hash" to "0".repeat(64), "ep" to ec)) })
        for ((name, build) in cases) {
            val p = parentMachine()
            val start = p.start(parent.publicKey, now)
            assertNull(name, p.onHello(relay(build(start.rendezvous.scope), start.rendezvous.token), now + 2_000))
            assertEquals(name, PS.Cancelled(CancelReason.BAD_MESSAGE), p.state)
            assertFalse(name, p.holdsSecrets)
        }
        // Control: the same builder with nothing changed is accepted.
        val p = parentMachine(); val start = p.start(parent.publicKey, now)
        assertNotNull(p.onHello(hello(start.rendezvous.scope, child.publicKey, ec, child), now + 2_000))
    }

    @Test fun badM2sAreRefusedCancelAndDeleteTheChildKey() {
        val stranger = FamilySecurity.newSigningIdentity()
        val cases = listOf<Pair<String, (String, String, String) -> JSONObject>>(
            "substituted Ep (signature no longer valid)" to { scope, k, ec ->
                command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey())).also { r ->
                    val env = r.getJSONObject("envelope"); env.put("payload", JSONObject(env.getString("payload")).put("ep", otherKey()).toString()) } },
            "signed by another parent key" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey()), stranger) },
            "k_hash of another key" to { scope, _, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(otherKey()), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey())) },
            "ec_hash of another key" to { scope, k, _ -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(otherKey()), "ep" to otherKey())) },
            "earlier QR (other scope)" to { _, k, ec -> command("pair_reply", GuardEngine.getChannelTopicForToken(PairingCrypto.newPairingSecret()), mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey())) },
            "unpadded Ep" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey().trimEnd('='))) },
            "Ep equal to Ec" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to ec)) },
            "stale" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey()), ts = now - 21 * 60_000L) },
            "future" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey()), ts = now + 3_000 + 120_001L) },
            "pair_complete instead" to { scope, k, _ -> command("pair_complete", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "new_pin" to verifier)) },
            "wrapper id differs" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey())).put("event_id", "control:x") },
            "envelope not a relay control record" to { scope, k, ec -> command("pair_reply", scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(ec), "ep" to otherKey())).apply { remove("relay_control") } })
        for ((name, build) in cases) {
            created.clear(); deleted.clear()
            val p = parentMachine(); val c = childMachine()
            val start = p.start(parent.publicKey, now)
            val r1 = inner(c.start(start.qrText, "Pixel 7", now + 1_000)!!.hello)
            assertFalse(name, c.onReply(relay(build(start.rendezvous.scope, r1.getString("k"), r1.getString("ec")), start.rendezvous.token), now + 3_000))
            assertChildCancelled(c, CancelReason.BAD_MESSAGE)
            assertNull(name, c.confirm(now + 4_000))
        }
        // Control: a correct hand-built M2 is accepted by the same harness.
        created.clear(); deleted.clear()
        val c = childMachine(); val start = parentMachine().start(parent.publicKey, now)
        val r1 = inner(c.start(start.qrText, "Pixel 7", now + 1_000)!!.hello)
        assertTrue(c.onReply(command("pair_reply", start.rendezvous.scope, mapOf("k_hash" to Pairing.sha256Hex(r1.getString("k")), "ec_hash" to Pairing.sha256Hex(r1.getString("ec")), "ep" to otherKey())), now + 3_000))
    }

    @Test fun anOutOfTurnM2CancelsButAByteIdenticalResendIsIgnored() {
        val (p, c, sm) = replied()
        assertFalse(c.onReply(JSONObject(sm.second.toString()), now + 3_500))   // identical resend
        assertTrue(c.state is CS.ShowingDigits && p.state is PS.EnterDigits)
        val other = command("pair_reply", sm.first.rendezvous.scope, mapOf("k_hash" to payload(sm.second).getString("k_hash"),
            "ec_hash" to payload(sm.second).getString("ec_hash"), "ep" to otherKey()))
        assertFalse(c.onReply(other, now + 3_600))
        assertChildCancelled(c, CancelReason.BAD_MESSAGE)
    }

    @Test fun expiredQrIsRefusedOnBothSides() {
        val p = parentMachine()
        val start = p.start(parent.publicKey, now)
        val exp = now + PairingCrypto.QR_LIFETIME_MS
        val m1 = childMachine().start(start.qrText, "Pixel 7", now + 1_000)!!.hello
        assertNull(p.onHello(m1, exp))
        assertParentCancelled(p, CancelReason.EXPIRED)
        val idle = parentMachine(); idle.start(parent.publicKey, now); idle.expire(exp - 1); assertTrue(idle.state is PS.ShowingQr)
        idle.expire(exp); assertEquals(PS.Cancelled(CancelReason.EXPIRED), idle.state)

        created.clear(); deleted.clear()
        val c = childMachine()
        assertNull(c.start(start.qrText, "Pixel 7", exp))
        assertEquals(CS.Cancelled(CancelReason.EXPIRED), c.state)
        assertTrue(created.isEmpty())                                          // no key made for an expired QR
        val junk = childMachine()
        assertNull(junk.start("IGGQ1.not-a-qr", "Pixel 7", now)); assertEquals(CS.Cancelled(CancelReason.BAD_MESSAGE), junk.state)
        // Child waiting for M2 past expiry + skew.
        val late = childMachine(); late.start(start.qrText, "Pixel 7", now + 1_000)
        late.expire(exp + Pairing.CLOCK_SKEW_MS - 1); assertEquals(CS.AwaitingReply, late.state)
        late.expire(exp + Pairing.CLOCK_SKEW_MS)
        assertChildCancelled(late, CancelReason.EXPIRED)
    }

    @Test fun keyTextsHaveOneCanonicalFormOnBothSides() {
        val padded = parent.publicKey
        val unpadded = padded.trimEnd('=')
        assertNotEquals(padded, unpadded)
        // PairingCrypto accepts both forms (as of fb0814e); Pairing accepts only the padded one from the other phone.
        assertTrue(PairingCrypto.validP256Key(unpadded))
        assertEquals(padded, Pairing.canonicalKey(unpadded))
        assertTrue(Pairing.isCanonicalKey(padded)); assertFalse(Pairing.isCanonicalKey(unpadded))
        // Why it matters: the two texts give different transcripts.
        val eph = PairingCrypto.newEphemeralKeyPair(); val ep = PairingCrypto.publicKeyText(eph.public); val ec = otherKey(); val k = otherKey()
        val s = PairingCrypto.newPairingSecret()
        assertNotEquals(PairingCrypto.familyToken(eph, s, padded, k, ep, ec), PairingCrypto.familyToken(eph, s, unpadded, k, ep, ec))
        // Parent: its own key is normalised, so the QR always carries the padded text.
        val fromUnpadded = parentMachine(secret = { s }).start(unpadded, now)
        assertEquals(parentMachine(secret = { s }).start(padded, now).qrText, fromUnpadded.qrText)
        assertEquals(padded, fromUnpadded.qrText.split('.')[2])
        // Child: a QR whose P is unpadded is refused before any key is made.
        val c = childMachine()
        assertNull(c.start("IGGQ1.$s.$unpadded.${now + 60_000}", "Pixel 7", now))
        assertEquals(CS.Cancelled(CancelReason.BAD_MESSAGE), c.state); assertTrue(created.isEmpty())
        // Child: its own key text is normalised before it enters M1 and the signed prefix.
        val real = FamilySecurity.createChildSigningKey(ctx)
        val sloppy = FamilySecurity.ChildSigningKey(real.alias, real.publicKey.trimEnd('='))
        val start = parentMachine().start(padded, now)
        val m1 = childMachine(key = { sloppy }).start(start.qrText, "Pixel 7", now + 1_000)!!.hello
        assertEquals(real.publicKey, inner(m1).getString("k"))
        assertNotNull(FamilySecurity.readChildRecord(m1, real.publicKey, padded, now + 1_000))
        // M1 with unpadded K/Ec and M2 with unpadded Ep are refused: see badM1sAreRefusedAndCancel and badM2sAreRefusedCancelAndDeleteTheChildKey.
    }

    @Test fun badM3sAndAMissingM3CancelTheChildAndDeleteItsKey() {
        val stranger = FamilySecurity.newSigningIdentity()
        val cases = listOf<Pair<String, (ParentPairing.Result, String, String) -> JSONObject>>(
            "wrong k_hash" to { r, _, _ -> command("pair_complete", r.family.scope, mapOf("new_pin" to verifier, "k_hash" to Pairing.sha256Hex(otherKey())), ts = now + 5_000) },
            "signed by another key" to { r, k, _ -> command("pair_complete", r.family.scope, mapOf("new_pin" to verifier, "k_hash" to Pairing.sha256Hex(k)), stranger, ts = now + 5_000) },
            "pairing scope instead of the family scope" to { _, k, pairingScope -> command("pair_complete", pairingScope, mapOf("new_pin" to verifier, "k_hash" to Pairing.sha256Hex(k)), ts = now + 5_000) },
            "a pair_reply instead" to { r, k, _ -> command("pair_reply", r.family.scope, mapOf("k_hash" to Pairing.sha256Hex(k), "ec_hash" to Pairing.sha256Hex(k), "ep" to otherKey()), ts = now + 5_000) })
        for ((name, build) in cases) {
            created.clear(); deleted.clear()
            val (p, c, sm) = replied()
            assertTrue(p.digitsEntered(digitsOf(c)))
            assertNotNull(c.confirm(now + 4_000))
            val result = p.accept(verifier, now + 5_000)!!
            assertNull(name, c.onComplete(build(result, result.childKey, sm.first.rendezvous.scope), now + 6_000))
            assertChildCancelled(c, CancelReason.BAD_MESSAGE)
        }
        // M3 never arrives: cancelled at 10 minutes after the child's own confirm, key deleted.
        created.clear(); deleted.clear()
        val (_, c, _) = replied()
        val confirmAt = now + 4_000
        assertNotNull(c.confirm(confirmAt))
        c.expire(confirmAt + Pairing.CONFIRM_WINDOW_MS - 1); assertEquals(CS.AwaitingComplete, c.state)
        c.expire(confirmAt + Pairing.CONFIRM_WINDOW_MS)
        assertChildCancelled(c, CancelReason.EXPIRED)
        assertEquals(listOf(created.single().alias), deleted)
    }

    @Test fun anM3BeforeTheChildsOwnConfirmIsRefused() {
        val (p, c, _) = replied()
        assertTrue(p.digitsEntered(digitsOf(c)))
        val result = p.accept(verifier, now + 5_000)!!
        assertNull(c.onComplete(relay(result.complete, result.family.token), now + 6_000))
        assertChildCancelled(c, CancelReason.BAD_MESSAGE)
    }

    @Test fun acceptNeedsDigitsDigitsCountOnceAndTheParentDeadlineHolds() {
        val (p, c, _) = replied()
        assertNull(p.accept(verifier, now + 4_000))
        assertTrue(p.digitsEntered(digitsOf(c)))
        assertFalse(p.digitsEntered(digitsOf(c)))                               // only once
        assertFalse(p.digitsEntered("000000")); assertFalse(p.digitsEntered("000001")); assertFalse(p.digitsEntered("000002"))
        assertTrue(p.state is PS.ReadyToAccept)                                 // later entries change nothing
        try { p.accept("not a verifier", now + 4_000); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(p.state is PS.ReadyToAccept)
        assertNull(p.accept(verifier, now + 2_000 + Pairing.CONFIRM_WINDOW_MS)) // M2 was sent at now + 2_000
        assertParentCancelled(p, CancelReason.EXPIRED)
    }

    @Test fun cancelOnEitherSideLeavesNothingPinnedOrPersisted() {
        val (p, c, sm) = replied()
        p.cancel()
        assertParentCancelled(p, CancelReason.USER_CANCEL)
        assertNull(p.onHello(childMachine().start(sm.first.qrText, "Other", now + 3_000)?.hello ?: JSONObject(), now + 3_000))
        assertEquals(PS.Cancelled(CancelReason.USER_CANCEL), p.state)
        created.removeAt(created.size - 1)
        assertNotNull(c.confirm(now + 4_000))
        c.cancel()
        assertChildCancelled(c, CancelReason.USER_CANCEL)
        assertNull(c.onComplete(JSONObject(), now + 5_000))
        c.cancel(); assertEquals(1, deleted.size)                               // deleted once
    }

    @Test fun terminalStatesRefuseEverythingAndMachinesAreSingleUse() {
        val (p, c, sm) = replied()
        assertTrue(p.digitsEntered(digitsOf(c)))
        val family = c.confirm(now + 4_000)!!
        val result = p.accept(verifier, now + 5_000)!!
        assertNotNull(c.onComplete(relay(result.complete, family.token), now + 6_000))
        val other = childMachine().start(sm.first.qrText, "Other", now + 6_500)!!
        assertNull(p.onHello(other.hello, now + 7_000)); assertEquals(PS.Accepted, p.state)   // after accept: ignored, not cancelled
        assertNull(p.accept(verifier, now + 7_000)); assertFalse(p.digitsEntered("123456"))
        p.cancel(); assertEquals(PS.Accepted, p.state)
        assertNull(c.onComplete(relay(result.complete, family.token), now + 7_000)); assertFalse(c.onReply(sm.second, now + 7_000))
        assertNull(c.confirm(now + 7_000))
        c.cancel(); c.expire(now + 60 * 60_000L); assertEquals(CS.Paired, c.state)
        assertTrue(deleted.isEmpty())                                          // the paired key is never deleted
        for (restart in listOf<() -> Unit>({ p.start(parent.publicKey, now) }, { c.start(sm.first.qrText, "Pixel 7", now) }))
            try { restart(); fail() } catch (_: IllegalStateException) { }
        val cancelled = childMachine(); cancelled.cancel(); assertEquals(CS.Cancelled(CancelReason.USER_CANCEL), cancelled.state)
        try { cancelled.start(sm.first.qrText, "Pixel 7", now); fail() } catch (_: IllegalStateException) { }
    }

    @Test fun aKeystoreFailureCancelsTheChild() {
        val start = parentMachine().start(parent.publicKey, now)
        TestKeyStore.failEcGeneration = true
        val c = childMachine()
        assertNull(c.start(start.qrText, "Pixel 7", now + 1_000))
        assertEquals(CS.Cancelled(CancelReason.KEY_UNAVAILABLE), c.state)
        assertTrue(created.isEmpty() && deleted.isEmpty())
        TestKeyStore.failEcGeneration = false
        val d = ChildPairing({ FamilySecurity.createChildSigningKey(ctx).also { created += it } }, { deleted += it; FamilySecurity.deleteChildSigningKey(ctx, it) },
            palette, sign = { _, _ -> throw SecurityException("Synthetic signing failure") })
        assertNull(d.start(start.qrText, "Pixel 7", now + 1_000))
        assertChildCancelled(d, CancelReason.KEY_UNAVAILABLE)                  // the key made before signing failed is deleted
    }

    // The fixed vector of PairingCryptoTest cannot run end to end: its P and K have no private keys in the tests, and the
    // machines must sign with both. What can be checked: with the vector's S, Ep and Ec injected, both machines feed exactly
    // the message texts into PairingCrypto and agree.
    private val dec = Base64.getUrlDecoder()
    private val vectorS = "00112233445566778899aabbccddeeff"
    private val vectorEp = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDNzWGXVjcNopKNMrkRw29O-mwZauEszgIiI2bp-UpMuvU8-pGuR-MawqlktforiFNIBLGhKAB8ul98QmK5-7Lw=="
    private val vectorEc = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvPCH2ODvIg8xM16tTGPinJIiMlKd1uyfv44TS9lFfi1GJ_FIygD36p79hnFXt_p12bI3Jmy1-7rKrPTuld3nmw=="
    private fun vectorPair(pub: String, priv: String) = KeyFactory.getInstance("EC").let {
        KeyPair(it.generatePublic(X509EncodedKeySpec(dec.decode(pub))), it.generatePrivate(PKCS8EncodedKeySpec(dec.decode(priv))))
    }
    @Test fun injectedVectorKeysFlowThroughBothMachines() {
        val ep = vectorPair(vectorEp, "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg15LHkAoC2z2FjaThltoMQm3Xis8hOJeiT8gT0ZQDR6ehRANCAAQM3NYZdWNw2iko0yuRHDb076bBlq4SzOAiIjZun5Sky69Tz6ka5H4xrCqWS1-iuIU0gEsaEoAHy6X3xCYrn7sv")
        val ec = vectorPair(vectorEc, "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg0Xij49DwlfNWO_9WEqaPKJFjA4-PN6cxFMIZ5YNe9C6hRANCAAS88IfY4O8iDzEzXq1MY-KckiIyUp3W7J-_jhNL2UV-LUYn8UjKAPfqnv2GcVe3-nXZsjcmbLX7usqs9O6V3eeb")
        val p = parentMachine(ephemeral = { ep }, secret = { vectorS }); val c = childMachine(ephemeral = { ec })
        val start = p.start(parent.publicKey, now)
        val m1 = relay(c.start(start.qrText, "Pixel 7", now + 1_000)!!.hello, start.rendezvous.token)
        assertEquals(vectorEc, inner(m1).getString("ec"))
        val m2 = p.onHello(m1, now + 2_000)!!
        assertEquals(vectorEp, payload(m2).getString("ep"))
        assertTrue(c.onReply(relay(m2, start.rendezvous.token), now + 3_000))
        val k = inner(m1).getString("k")
        val t1 = PairingCrypto.familyToken(ec, vectorS, parent.publicKey, k, vectorEp, vectorEc)
        assertEquals(PairingCrypto.pairingDigits(parent.publicKey, k, vectorEp, vectorEc, t1), digitsOf(c))
        assertTrue(p.digitsEntered(digitsOf(c)))
        assertNotNull(c.confirm(now + 4_000))
        assertEquals(t1, p.accept(verifier, now + 5_000)!!.token)
    }
}
