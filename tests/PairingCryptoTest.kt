package app.family.igguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** Pure JVM checks of the pairing building blocks and signed child records. The fixed vector was computed independently
 * with OpenSSL 3.2 (keys, ECDH) and Python's hmac/hashlib (HKDF, digits, pictures). */
class PairingCryptoTest {
    private val now = 1_800_000_000_000L
    private val hexBytes = { s: String -> s.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder()

    // Fixed P-256 test keys (base64url SPKI as transmitted; PKCS8 for the two ephemeral private keys). Test-only, never used elsewhere.
    private val P = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHpEiYAmwzkKWsD51_propYI7WZBY_0c-J8S7WNNP8Pgrdg07nUu_RpzI7IcmnL-IHk8YBOwq7V1qmFAJ0I8lZg=="
    private val K = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJiXFEwzlfWqcsngtPWGH1cx8bxRCtbR0A36rPwvNepayrYX3k7ByRfYRxa1944kqxPBpKcrRxMWzI71LnXohBQ=="
    private val EP = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDNzWGXVjcNopKNMrkRw29O-mwZauEszgIiI2bp-UpMuvU8-pGuR-MawqlktforiFNIBLGhKAB8ul98QmK5-7Lw=="
    private val EC = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvPCH2ODvIg8xM16tTGPinJIiMlKd1uyfv44TS9lFfi1GJ_FIygD36p79hnFXt_p12bI3Jmy1-7rKrPTuld3nmw=="
    private val EP_PRIVATE = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg15LHkAoC2z2FjaThltoMQm3Xis8hOJeiT8gT0ZQDR6ehRANCAAQM3NYZdWNw2iko0yuRHDb076bBlq4SzOAiIjZun5Sky69Tz6ka5H4xrCqWS1-iuIU0gEsaEoAHy6X3xCYrn7sv"
    private val EC_PRIVATE = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg0Xij49DwlfNWO_9WEqaPKJFjA4-PN6cxFMIZ5YNe9C6hRANCAAS88IfY4O8iDzEzXq1MY-KckiIyUp3W7J-_jhNL2UV-LUYn8UjKAPfqnv2GcVe3-nXZsjcmbLX7usqs9O6V3eeb"
    private val S = "00112233445566778899aabbccddeeff"
    private val palette = listOf("🐼", "🦁", "🐬", "🦊", "🐨", "🐯", "🦄", "🐝", "🚀", "🌈", "🍀", "🌻", "🍎", "🍉", "⚽", "🎸")
    private fun pair(publicText: String, privateText: String) = KeyFactory.getInstance("EC").let {
        KeyPair(it.generatePublic(X509EncodedKeySpec(decoder.decode(publicText))), it.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privateText))))
    }
    private val parentEphemeral get() = pair(EP, EP_PRIVATE)
    private val childEphemeral get() = pair(EC, EC_PRIVATE)
    private fun otherKey() = PairingCrypto.publicKeyText(PairingCrypto.newEphemeralKeyPair().public)

    @Test fun hkdfMatchesRfc5869Sha256Vectors() {
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(PairingCrypto.hkdfSha256(ByteArray(22) { 0x0b }, hexBytes("000102030405060708090a0b0c"), hexBytes("f0f1f2f3f4f5f6f7f8f9"), 42)))
        assertEquals("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            hex(PairingCrypto.hkdfSha256(ByteArray(80) { it.toByte() }, ByteArray(80) { (0x60 + it).toByte() }, ByteArray(80) { (0xb0 + it).toByte() }, 82)))
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hex(PairingCrypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)))
        for (bad in listOf(0, 255 * 32 + 1)) try { PairingCrypto.hkdfSha256(ByteArray(1), ByteArray(0), ByteArray(0), bad); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun fixedVectorForSecondImplementations() {
        assertEquals("32a562401042d6166e833809d296764efdc893c6c3cb06d4824edf49259cb682", hex(PairingCrypto.sharedSecret(parentEphemeral, EC)))
        assertEquals("32a562401042d6166e833809d296764efdc893c6c3cb06d4824edf49259cb682", hex(PairingCrypto.sharedSecret(childEphemeral, EP)))
        val t1 = PairingCrypto.familyToken(parentEphemeral, S, P, K, EP, EC)
        assertEquals("72d7eeb5e03abe894f5cc15f26c252ef", t1)
        assertEquals(t1, PairingCrypto.familyToken(childEphemeral, S, P, K, EP, EC))
        assertEquals("371078", PairingCrypto.pairingDigits(P, K, EP, EC, t1))
        assertEquals("9 14 1 8", PairingCrypto.pairingPictures(P, K, EP, EC, t1, (0..15).map { it.toString() }))
        assertEquals("🌈 ⚽ 🦁 🚀", PairingCrypto.pairingPictures(P, K, EP, EC, t1, palette))
    }

    @Test fun freshEphemeralKeysAgreeBothWaysAndGiveAValidToken() {
        val parent = PairingCrypto.newEphemeralKeyPair(); val child = PairingCrypto.newEphemeralKeyPair()
        val ep = PairingCrypto.publicKeyText(parent.public); val ec = PairingCrypto.publicKeyText(child.public)
        assertTrue(PairingCrypto.validP256Key(ep) && !ep.contains('.'))
        assertArrayEquals(PairingCrypto.sharedSecret(parent, ec), PairingCrypto.sharedSecret(child, ep))
        val secret = PairingCrypto.newPairingSecret()
        val t1 = PairingCrypto.familyToken(parent, secret, P, K, ep, ec)
        assertEquals(t1, PairingCrypto.familyToken(child, secret, P, K, ep, ec))
        assertTrue(Regex("[0-9a-f]{32}").matches(t1) && FamilySecurity.validToken(t1))
        assertTrue(Regex("[0-9a-f]{32}").matches(secret) && FamilySecurity.validToken(secret))
        assertNotEquals(secret, PairingCrypto.newPairingSecret())
    }

    @Test fun everyTranscriptInputChangesTheToken() {
        val base = PairingCrypto.familyToken(parentEphemeral, S, P, K, EP, EC)
        val other = PairingCrypto.newEphemeralKeyPair(); val otherText = PairingCrypto.publicKeyText(other.public)
        val changed = listOf(
            PairingCrypto.familyToken(parentEphemeral, "00112233445566778899aabbccddeef0", P, K, EP, EC),
            PairingCrypto.familyToken(parentEphemeral, S, otherKey(), K, EP, EC),
            PairingCrypto.familyToken(parentEphemeral, S, P, otherKey(), EP, EC),
            PairingCrypto.familyToken(childEphemeral, S, P, K, otherText, EC),
            PairingCrypto.familyToken(parentEphemeral, S, P, K, EP, otherText),
            // Same keys, P and K swapped: the order of the info fields matters.
            PairingCrypto.familyToken(parentEphemeral, S, K, P, EP, EC))
        for (t in changed) assertNotEquals(base, t)
        assertEquals(changed.size, changed.toSet().size)
        for (bad in listOf<() -> Unit>(
            { PairingCrypto.familyToken(parentEphemeral, "00112233445566778899AABBCCDDEEFF", P, K, EP, EC) },
            { PairingCrypto.familyToken(parentEphemeral, S.take(30), P, K, EP, EC) },
            { PairingCrypto.familyToken(parentEphemeral, S, P, K, EP, EP) },
            { PairingCrypto.familyToken(other, S, P, K, EP, EC) },
            { PairingCrypto.familyToken(parentEphemeral, S, "garbage", K, EP, EC) }))
            try { bad(); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun peerKeysOffCurveOnOtherCurvesOrMalformedAreRefused() {
        val raw = decoder.decode(EC)
        val offCurve = encoder.encodeToString(raw.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        val p384 = encoder.encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded)
        val rsa = encoder.encodeToString(KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public.encoded)
        val trailing = encoder.encodeToString(raw + byteArrayOf(0))
        val standardBase64 = Base64.getEncoder().encodeToString(raw)
        assertTrue(FamilySecurity.validPublicKey(p384)) // the older check accepts any EC curve; the pairing check is stricter
        for (bad in listOf(offCurve, p384, rsa, trailing, "", "garbage", EP_PRIVATE, "$EC\n", " $EC", EC.replace('-', '+'), standardBase64.takeIf { it != EC } ?: "x")) {
            assertFalse(bad, PairingCrypto.validP256Key(bad))
            try { PairingCrypto.sharedSecret(parentEphemeral, bad); fail(bad) } catch (_: IllegalArgumentException) { }
        }
        assertFalse(PairingCrypto.validP256Key(null))
        assertTrue(PairingCrypto.validP256Key(EC.trimEnd('=')))
    }

    @Test fun digitsAndPicturesAreStableSeparateAndBindEveryField() {
        val t1 = "72d7eeb5e03abe894f5cc15f26c252ef"
        val digits = PairingCrypto.pairingDigits(P, K, EP, EC, t1)
        val pictures = PairingCrypto.pairingPictures(P, K, EP, EC, t1, palette)
        assertEquals(digits, PairingCrypto.pairingDigits(P, K, EP, EC, t1))
        assertEquals(pictures, PairingCrypto.pairingPictures(P, K, EP, EC, t1, palette))
        assertTrue(Regex("[0-9]{6}").matches(digits))
        // Separate labels: the pictures digest is not the digits digest.
        val x = "$P\n$K\n$EP\n$EC\n" + hex(MessageDigest.getInstance("SHA-256").digest(t1.toByteArray()))
        val pictureDigest = MessageDigest.getInstance("SHA-256").digest("igguard-pair-pictures-v1\n$x".toByteArray())
        val digitDigest = MessageDigest.getInstance("SHA-256").digest("igguard-pair-digits-v1\n$x".toByteArray())
        assertFalse(pictureDigest.contentEquals(digitDigest))
        assertNotEquals(digits, "%06d".format(java.math.BigInteger(1, pictureDigest.copyOf(8)).mod(java.math.BigInteger.valueOf(1_000_000)).toLong()))
        assertEquals(digits, "%06d".format(java.math.BigInteger(1, digitDigest.copyOf(8)).mod(java.math.BigInteger.valueOf(1_000_000)).toLong()))
        val o = otherKey()
        for (changed in listOf(listOf(o, K, EP, EC, t1), listOf(P, o, EP, EC, t1), listOf(P, K, o, EC, t1), listOf(P, K, EP, o, t1), listOf(P, K, EP, EC, "72d7eeb5e03abe894f5cc15f26c252ee"))) {
            assertNotEquals(digits, PairingCrypto.pairingDigits(changed[0], changed[1], changed[2], changed[3], changed[4]))
            assertNotEquals(pictures, PairingCrypto.pairingPictures(changed[0], changed[1], changed[2], changed[3], changed[4], palette))
        }
        for (bad in listOf<() -> Unit>({ PairingCrypto.pairingDigits("$P\n", K, EP, EC, t1) }, { PairingCrypto.pairingDigits(P, K, EP, EC, "not-hex") },
                { PairingCrypto.pairingPictures(P, K, EP, EC, t1, palette.take(15)) }))
            try { bad(); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun qrRoundTripAndEveryRejection() {
        val exp = now + PairingCrypto.QR_LIFETIME_MS
        val qr = PairingCrypto.buildPairingQr(S, P, exp)
        assertEquals("IGGQ1.$S.$P.$exp", qr)
        assertEquals(PairingCrypto.PairingQr(S, P, exp), PairingCrypto.parsePairingQr(qr, now))
        assertTrue(qr.length < 200)
        // base64url (with '=' padding) cannot contain '.', so the separator is unambiguous.
        repeat(200) { assertFalse(encoder.encodeToString(ByteArray(it) { b -> (b * 37 + it).toByte() }).contains('.')) }
        val p384 = encoder.encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded)
        val limit = now + PairingCrypto.QR_LIFETIME_MS + PairingCrypto.QR_CLOCK_SKEW_MS
        assertNotNull(PairingCrypto.parsePairingQr("IGGQ1.$S.$P.$limit", now))
        for (bad in listOf("IGGQ2.$S.$P.$exp", "iggq1.$S.$P.$exp", "IGGQ1.$S.$P", "IGGQ1.$S.$P.$exp.1", ".IGGQ1.$S.$P.$exp", "IGGQ1.$S..$P.$exp",
                "IGGQ1.${S.uppercase()}.$P.$exp", "IGGQ1.${S.drop(1)}.$P.$exp", "IGGQ1.${S}0.$P.$exp", "IGGQ1.$S.$p384.$exp", "IGGQ1.$S.garbage.$exp",
                "IGGQ1.$S.$EP_PRIVATE.$exp", "IGGQ1.$S.$P.0", "IGGQ1.$S.$P.-$exp", "IGGQ1.$S.$P.+$exp", "IGGQ1.$S.$P.0$exp", "IGGQ1.$S.$P.${exp}x",
                "IGGQ1.$S.$P.99999999999999999999", "IGGQ1.$S.$P.$now", "IGGQ1.$S.$P.${now - 1}", "IGGQ1.$S.$P.${limit + 1}", "IGGQ1.$S.$P.$exp ",
                " IGGQ1.$S.$P.$exp", "IGGQ1.$S.$P.$exp\n", "IGGQ1.$S.${P}${"A".repeat(300)}.$exp", ""))
            assertNull(bad, PairingCrypto.parsePairingQr(bad, now))
        for (bad in listOf<() -> Unit>({ PairingCrypto.buildPairingQr(S.uppercase(), P, exp) }, { PairingCrypto.buildPairingQr(S, p384, exp) }, { PairingCrypto.buildPairingQr(S, P, 0) }))
            try { bad(); fail() } catch (_: IllegalArgumentException) { }
    }

    // ---------------- signed child records (software keys; the Keystore path is in ChildSigningKeyTest) ----------------
    private val parent = FamilySecurity.newSigningIdentity()
    private val child = FamilySecurity.newSigningIdentity()
    private fun alert(reason: String = "heartbeat_ok", ts: Long = now) = JSONObject().put("type", "alert").put("event_id", UUID.randomUUID().toString())
        .put("reason", reason).put("sender", "Pixel").put("text", "").put("ts_ms", ts)
    private fun control(type: String, ts: Long = now) = JSONObject().put("type", type).put("command_id", UUID.randomUUID().toString()).put("scope", "ig_hk_test").put("ts_ms", ts)
    private fun scores(sent: Long = now) = JSONObject().put("type", "scores_mode_report").put("command_id", UUID.randomUUID().toString()).put("scope", "ig_hk_test").put("sent_at_ms", sent)
    private fun captured(capturedAt: Long = now) = JSONObject().put("type", "captured_message").put("event_id", UUID.randomUUID().toString())
        .put("reason", "stranger_message").put("ts_ms", now - 40 * 86_400_000L).put("captured_at_ms", capturedAt)
    private fun idOf(r: JSONObject) = if (r.has("command_id")) "control:" + r.getString("command_id") else r.getString("event_id")
    private fun wrap(r: JSONObject, p: String = parent.publicKey, k: FamilySecurity.SigningIdentity = child, text: String = r.toString()) =
        JSONObject().put("type", "alert").put("event_id", idOf(r)).put("child_signed", text)
            .put("child_sig", encoder.encodeToString(FamilySecurity.signBytes(FamilySecurity.childRecordSignedBytes(text, p, k.publicKey), k.privateKey)))
    private fun read(w: JSONObject, k: String = child.publicKey, p: String = parent.publicKey) = FamilySecurity.readChildRecord(w, k, p, now)

    @Test fun signedBytesAreExactlyTheDocumentedString() {
        val pHash = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(P.toByteArray()))
        val kHash = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(K.toByteArray()))
        assertEquals("750tGzU6Gy2LOJfrPB88AALk5kYsXghKMFkKrTUR3ps=", pHash)
        assertEquals("F0xO4o2UC-uq8gBQzJciIHOrZ_pH9Vo6FBVdH1W2QGs=", kHash)
        assertEquals("igguard-child-record-v1\n$pHash\n$kHash\n{\"type\":\"alert\"}", String(FamilySecurity.childRecordSignedBytes("{\"type\":\"alert\"}", P, K), Charsets.UTF_8))
    }

    @Test fun everyChildRecordKindRoundTrips() {
        val hello = control("pair_hello").put("k", child.publicKey).put("ec", EC)
        for ((r, time) in listOf(alert() to now, alert("tamper_attempt") to now, alert("pairing_verified") to now, alert("stranger_message", now - 8 * 86_400_000L) to now - 8 * 86_400_000L,
                captured() to now, control("capture_ack", now + 120_000L) to now + 120_000L, control("sync_child_handle") to now,
                scores(now - RiskLimits.REPORT_HORIZON_MS) to now - RiskLimits.REPORT_HORIZON_MS, hello to now)) {
            val got = read(wrap(r))
            assertNotNull(r.toString(), got)
            assertEquals(r.getString("type"), got!!.type)
            assertEquals(idOf(r).removePrefix("control:"), got.id)
            assertEquals(time, got.timeMs)
            assertEquals(r.getString("type"), got.record.getString("type"))
        }
    }

    @Test fun forgedAlteredMalformedAndOutOfWindowRecordsAreRefused() {
        val attacker = FamilySecurity.newSigningIdentity()
        val good = wrap(alert())
        assertNotNull(read(good))
        val cases = listOf(
            wrap(alert(), k = attacker),                                                   // signed by another key
            JSONObject(good.toString()).put("child_signed", good.getString("child_signed").replace("Pixel", "Pixe1")), // altered record
            JSONObject(good.toString()).put("child_sig", wrap(alert()).getString("child_sig")), // signature of another record
            JSONObject(good.toString()).put("event_id", UUID.randomUUID().toString()),     // altered wrapper id
            JSONObject(good.toString()).apply { remove("event_id") },
            JSONObject(good.toString()).apply { remove("child_signed") },
            JSONObject(good.toString()).apply { remove("child_sig") },
            JSONObject(good.toString()).put("child_signed", JSONObject(good.getString("child_signed"))), // not a string
            JSONObject(good.toString()).put("child_sig", 12345),
            JSONObject(good.toString()).put("child_sig", "!!notbase64"),
            wrap(control("sync_trusted")), wrap(control("key_rotated")), wrap(JSONObject(alert().toString()).put("type", "alert_receipt")), // parent / unknown types
            wrap(alert("remote_reset")), wrap(alert().apply { remove("reason") }), wrap(captured().put("reason", "heartbeat_ok")),
            wrap(alert().put("event_id", "has space")), wrap(alert().put("event_id", "a".repeat(161))), wrap(alert().put("event_id", "")),
            wrap(control("capture_ack").put("command_id", "")), wrap(control("capture_ack").put("command_id", "bad/id")),
            wrap(alert(ts = now + 120_001L)), wrap(alert(ts = now - 8 * 86_400_000L - 1)), wrap(alert(ts = 0)), wrap(alert().put("ts_ms", now.toString())),
            wrap(captured(now - 8 * 86_400_000L - 1)), wrap(captured().apply { remove("captured_at_ms") }),
            wrap(scores(now - RiskLimits.REPORT_HORIZON_MS - 1)), wrap(scores(now + 120_001L)),
            wrap(control("capture_ack", now - 86_400_000L - 1)), wrap(control("sync_child_handle", now - 86_400_000L - 1)), wrap(control("pair_hello").put("k", child.publicKey).put("ec", EC).put("ts_ms", now - 86_400_000L - 1)),
            wrap(control("pair_hello").put("k", attacker.publicKey).put("ec", EC)), wrap(control("pair_hello").put("k", child.publicKey).put("ec", "garbage")),
            wrap(control("capture_ack").put("pad", "x".repeat(16_384))),                   // small control record over 16 KiB
            wrap(alert().put("text", "x".repeat(1_048_576))))                              // over the 1 MiB transport bound
        for (c in cases) assertNull(c.toString().take(300), read(c))
        assertNotNull(read(wrap(alert().put("text", "x".repeat(20_000)))))                 // large alerts and reports still pass
        assertNotNull(read(wrap(scores().put("pad", "x".repeat(20_000)))))
        assertNull(read(good, k = "garbage"))
        assertNull(read(good, p = "garbage"))
    }

    @Test fun aRecordIsBoundToItsFamilyAndItsChildKey() {
        val otherParent = FamilySecurity.newSigningIdentity()
        val otherChild = FamilySecurity.newSigningIdentity()
        val w = wrap(alert())
        assertNotNull(read(w))
        assertNull(read(w, p = otherParent.publicKey))                    // replayed into another family
        assertNull(read(w, k = otherChild.publicKey))                     // claimed for another child
        assertNull(read(w, k = child.publicKey.trimEnd('=')))             // same key, different pinned text: the hash binds the text
        assertNull(read(wrap(alert(), p = otherParent.publicKey)))        // signed for another family, read in this one
    }

    // ---------------- parent pairing commands ----------------
    private fun command(type: String, ts: Long = now) = JSONObject().put("type", type).put("scope", "ig_hk_pair").put("command_id", UUID.randomUUID().toString()).put("ts_ms", ts)
    private fun readCmd(c: JSONObject) = FamilySecurity.readCommand(FamilySecurity.signCommand(c, parent.privateKey), parent.publicKey, "ig_hk_pair", now)
    private fun sha(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    @Test fun pairReplyAndPairCompleteFieldsAreChecked() {
        assertTrue("pair_reply" in FamilySecurity.controlTypes && "pair_complete" in FamilySecurity.controlTypes)
        val reply = { command("pair_reply").put("k_hash", sha(K)).put("ec_hash", sha(EC)).put("ep", EP) }
        assertNotNull(readCmd(reply()))
        val verifier = FamilySecurity.passwordVerifier("ParentPassword928!")
        val complete = { command("pair_complete").put("new_pin", verifier).put("k_hash", sha(K)) }
        assertNotNull(readCmd(complete()))
        val p384 = encoder.encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded)
        for (bad in listOf(reply().apply { remove("k_hash") }, reply().put("k_hash", sha(K).uppercase()), reply().put("k_hash", sha(K).drop(1)),
                reply().put("ec_hash", 5), reply().apply { remove("ec_hash") }, reply().put("ep", p384), reply().put("ep", "garbage"), reply().apply { remove("ep") },
                replyAt(0), command("pair_reply", now - 21 * 60_000L).put("k_hash", sha(K)).put("ec_hash", sha(EC)).put("ep", EP),
                complete().put("new_pin", "plainPassword928!"), complete().apply { remove("new_pin") }, complete().put("k_hash", "x"), complete().apply { remove("k_hash") },
                command("pair_complete", now - 21 * 60_000L).put("new_pin", verifier).put("k_hash", sha(K))))
            assertNull(bad.toString(), readCmd(bad))
    }
    private fun replyAt(ts: Long) = command("pair_reply", ts).put("k_hash", sha(K)).put("ec_hash", sha(EC)).put("ep", EP)
}
