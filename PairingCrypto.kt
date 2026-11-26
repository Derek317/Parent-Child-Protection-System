package app.family.igguard

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure building blocks of the QR pairing (design m2-and-multichild, sections 2.2-2.4).
 * Key texts (P, K, Ep, Ec) are base64url SPKI strings exactly as transmitted; every string is hashed as its UTF-8 bytes.
 */
object PairingCrypto {
    const val QR_PREFIX = "IGGQ1"
    const val QR_LIFETIME_MS = 3 * 60_000L
    /** A QR expiring later than lifetime + this (clock difference between the two phones) is refused. */
    const val QR_CLOCK_SKEW_MS = 10 * 60_000L
    private const val QR_MAX_LENGTH = 400
    private val encoder = Base64.getUrlEncoder()
    private val decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()
    private val secretPattern = Regex("[0-9a-f]{32}")
    // The base64url alphabet plus padding: never contains '.', the QR separator, nor '\n', the transcript separator.
    private val keyTextPattern = Regex("[A-Za-z0-9_-]+={0,2}")
    private val expiryPattern = Regex("[1-9][0-9]{0,18}")

    // NIST P-256 (secp256r1) domain parameters, FIPS 186-4 D.1.2.3; a = p - 3, cofactor 1.
    private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val P256_N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val P256_GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val P256_GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    /** RFC 5869 HKDF-SHA256 (extract then expand). An empty [salt] means 32 zero bytes, as the RFC specifies. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (okm.size() < length) {
            mac.update(block); mac.update(info); mac.update(counter++.toByte())
            block = mac.doFinal()
            okm.write(block)
        }
        prk.fill(0)
        return okm.toByteArray().copyOf(length)
    }

    /** The key only if [text] is canonical SPKI of a P-256 point on the curve (not infinity, coordinates in range); else null. */
    fun p256PublicKey(text: String): ECPublicKey? = try {
        if (text.length !in 80..256 || !keyTextPattern.matches(text)) null
        else {
            val raw = decoder.decode(text)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(raw)) as ECPublicKey
            val params = key.params
            val point = key.w
            val x = point.affineX
            val y = point.affineY
            val curve = (params.curve.field as? ECFieldFp)?.p == P256_P && params.curve.a == P256_P - BigInteger.valueOf(3) &&
                params.curve.b == P256_B && params.order == P256_N && params.cofactor == 1 &&
                params.generator.affineX == P256_GX && params.generator.affineY == P256_GY
            val onCurve = point != ECPoint.POINT_INFINITY && x.signum() >= 0 && x < P256_P && y.signum() >= 0 && y < P256_P &&
                y.modPow(BigInteger.valueOf(2), P256_P) == x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(P256_B).mod(P256_P)
            key.takeIf { curve && onCurve && it.encoded.contentEquals(raw) }
        }
    } catch (_: Exception) { null }
    fun validP256Key(text: String?): Boolean = text != null && p256PublicKey(text) != null

    /** An in-memory P-256 key pair (not Keystore: Keystore EC keys cannot do ECDH before API 31). Drop it after [familyToken]. */
    fun newEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun publicKeyText(key: PublicKey): String = encoder.encodeToString(key.encoded)

    /** Z: the raw ECDH shared secret (32-byte x coordinate). */
    fun sharedSecret(own: KeyPair, peerPublicKey: String): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(own.private)
        doPhase(requireNotNull(p256PublicKey(peerPublicKey)) { "Invalid peer public key" }, true)
        generateSecret()
    }

    /**
     * T1 = hex(HKDF-SHA256(ikm = Z, salt = SHA-256("igguard-pair-salt-v1\n" + S),
     *                      info = "igguard-family-v1\n" + P + "\n" + K + "\n" + Ep + "\n" + Ec, length = 16)).
     * [own] is this phone's ephemeral pair; its public text must be [parentEphemeral] or [childEphemeral], the peer is the other.
     */
    fun familyToken(own: KeyPair, secret: String, parentKey: String, childKey: String, parentEphemeral: String, childEphemeral: String): String {
        require(secretPattern.matches(secret)) { "Invalid pairing secret" }
        require(listOf(parentKey, childKey, parentEphemeral, childEphemeral).all { validP256Key(it) }) { "Invalid pairing key" }
        require(parentEphemeral != childEphemeral) { "Ephemeral keys must differ" }
        val peer = when (publicKeyText(own.public)) {
            parentEphemeral -> childEphemeral
            childEphemeral -> parentEphemeral
            else -> throw IllegalArgumentException("Own ephemeral key is not in the transcript")
        }
        val z = sharedSecret(own, peer)
        try {
            val info = "igguard-family-v1\n$parentKey\n$childKey\n$parentEphemeral\n$childEphemeral".toByteArray(Charsets.UTF_8)
            return hex(hkdfSha256(z, sha256("igguard-pair-salt-v1\n$secret"), info, 16)).also { check(FamilySecurity.validToken(it)) }
        } finally { z.fill(0) }
    }

    /** X = P + "\n" + K + "\n" + Ep + "\n" + Ec + "\n" + hex(SHA-256(T1)), T1 hashed as its 32 ASCII hex characters. */
    private fun transcript(parentKey: String, childKey: String, parentEphemeral: String, childEphemeral: String, token: String): String {
        require(listOf(parentKey, childKey, parentEphemeral, childEphemeral).all { keyTextPattern.matches(it) } && secretPattern.matches(token))
        return "$parentKey\n$childKey\n$parentEphemeral\n$childEphemeral\n${hex(sha256(token))}"
    }
    /** Six digits: first 8 bytes of SHA-256("igguard-pair-digits-v1\n" + X), unsigned big-endian, mod 10^6, zero-padded.
     * Modulo bias: 2^64 is not a multiple of 10^6, so some values are more likely by at most 10^6 / 2^64 (about 5e-14). */
    fun pairingDigits(parentKey: String, childKey: String, parentEphemeral: String, childEphemeral: String, token: String): String {
        val digest = sha256("igguard-pair-digits-v1\n" + transcript(parentKey, childKey, parentEphemeral, childEphemeral, token))
        val value = digest.take(8).fold(0uL) { acc, byte -> (acc shl 8) or (byte.toULong() and 0xffuL) }
        return (value % 1_000_000uL).toString().padStart(6, '0')
    }
    /** Four pictures: bytes 0..3 of SHA-256("igguard-pair-pictures-v1\n" + X), each `byte % palette.size`, joined by spaces,
     * the same mapping as GuardEngine.safetyEmojis. [palette] is GuardEngine's EMOJI_PALETTE (16 entries; it divides 256, so no bias). */
    fun pairingPictures(parentKey: String, childKey: String, parentEphemeral: String, childEphemeral: String, token: String, palette: List<String>): String {
        require(palette.isNotEmpty() && 256 % palette.size == 0)
        val digest = sha256("igguard-pair-pictures-v1\n" + transcript(parentKey, childKey, parentEphemeral, childEphemeral, token))
        return (0..3).joinToString(" ") { palette[(digest[it].toInt() and 0xFF) % palette.size] }
    }

    // ---------------- QR payload: IGGQ1.<S>.<P>.<exp> ----------------
    data class PairingQr(val secret: String, val parentKey: String, val expiresAt: Long)
    /** S: 16 random bytes as 32 lowercase hex characters (passes FamilySecurity.validToken). */
    fun newPairingSecret(): String = hex(ByteArray(16).also { random.nextBytes(it) })
    fun buildPairingQr(secret: String, parentKey: String, expiresAt: Long): String {
        require(secretPattern.matches(secret) && validP256Key(parentKey) && expiresAt > 0) { "Invalid pairing QR fields" }
        return "$QR_PREFIX.$secret.$parentKey.$expiresAt"
    }
    /** Null on any deviation: no trimming, exactly four '.'-separated fields, expired at [now], or expiring more than
     * lifetime + clock skew (13 minutes) after [now]. */
    fun parsePairingQr(text: String, now: Long): PairingQr? {
        if (text.length > QR_MAX_LENGTH) return null
        val fields = text.split('.')
        if (fields.size != 4 || fields[0] != QR_PREFIX || !secretPattern.matches(fields[1]) || !validP256Key(fields[2]) || !expiryPattern.matches(fields[3])) return null
        val expiresAt = fields[3].toLongOrNull() ?: return null
        if (expiresAt <= now || expiresAt > now + QR_LIFETIME_MS + QR_CLOCK_SKEW_MS) return null
        return PairingQr(fields[1], fields[2], expiresAt)
    }
}
