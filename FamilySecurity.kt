package app.family.igguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.security.auth.x500.X500Principal

/** Parent authority is separate from the encryption token shared with a child. */
object FamilySecurity {
    val controlTypes = setOf("sync_trusted", "unlock_code", "key_rotated", "remote_reset_child", "alert_receipt", "capture_policy", "scores_mode_policy")
    private const val ITERATIONS = 600_000
    private val encoder = Base64.getUrlEncoder()
    private val decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()
    private val tokenPattern = Regex("[a-zA-Z0-9-]{8,64}")
    private val codePattern = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}")
    private const val PARENT_ALIAS = "ig_guard.parent_signing.ec.v1"
    private const val KEYSTORE_REFERENCE_PREFIX = "android-keystore:"
    private const val PENDING_IDENTITY = "pending"
    private val parentAliasPattern = Regex(Regex.escape(PARENT_ALIAS) + "(?:\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?")
    private val identityLock = Any()
    data class SigningIdentity(val privateKey: String, val publicKey: String)
    data class Invite(val token: String, val passwordVerifier: String, val parentPublicKey: String)

    private fun passwordHash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun passwordVerifier(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return listOf("pbkdf2-sha256", ITERATIONS.toString(), encoder.encodeToString(salt), encoder.encodeToString(passwordHash(password, salt))).joinToString(":")
    }
    private fun verifierParts(value: String): List<String>? = try {
        value.split(":").takeIf {
            it.size == 4 && it[0] == "pbkdf2-sha256" && it[1] == ITERATIONS.toString() &&
                decoder.decode(it[2]).size == 16 && decoder.decode(it[3]).size == 32
        }
    } catch (_: Exception) { null }
    fun isVerifier(value: String): Boolean = value.length < 256 && verifierParts(value) != null
    fun verifiesPassword(password: String, verifier: String): Boolean {
        val parts = verifierParts(verifier) ?: return false
        return MessageDigest.isEqual(passwordHash(password, decoder.decode(parts[2])), decoder.decode(parts[3]))
    }
    /** Software identities for JVM fixtures/protocol vectors; production uses loadOrCreateParentIdentity. */
    fun newSigningIdentity(): SigningIdentity {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return SigningIdentity(encoder.encodeToString(keys.private.encoded), encoder.encodeToString(keys.public.encoded))
    }
    private inline fun <T> identityOperation(action: () -> T): T = try { action() }
        catch (e: Exception) { throw SecurityException("Parent signing identity is unavailable; existing trust was retained", e) }

    private fun parentStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun identityMarker(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, "$PARENT_ALIAS.initialized"))
    private fun markerExists(marker: AtomicFile) = marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists() || File(marker.baseFile.path + ".new").exists()
    private fun recordIdentity(marker: AtomicFile, value: String) {
        val stream = marker.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            marker.finishWrite(stream)
            check(marker.openRead().use { it.readBytes().toString(Charsets.UTF_8) } == value) { "Identity marker was not committed" }
        } catch (e: Exception) { marker.failWrite(stream); throw e }
    }
    private fun verifyPossession(key: PrivateKey, publicKey: PublicKey) {
        val challenge = ByteArray(32).also { random.nextBytes(it) }
        val proof = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(challenge); sign() }
        check(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(challenge); verify(proof) }) { "Parent private/public key mismatch" }
    }
    private fun requirePublicMatch(expected: String, actual: PublicKey) {
        check(MessageDigest.isEqual(decoder.decode(expected), actual.encoded)) { "Pinned parent public key changed" }
    }
    private fun generateParentKey(alias: String) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false).build())
        }.generateKeyPair()
    }
    private fun storedParentIdentity(store: KeyStore, alias: String, expected: String?): SigningIdentity {
        val key = store.getKey(alias, null) as? PrivateKey ?: throw SecurityException("Required parent signing key is missing")
        check(key.encoded == null) { "Parent signing key is exportable" }
        val publicKey = store.getCertificate(alias)?.publicKey ?: throw SecurityException("Parent public-key certificate is missing")
        if (expected != null) requirePublicMatch(expected, publicKey)
        verifyPossession(key, publicKey)
        return SigningIdentity(KEYSTORE_REFERENCE_PREFIX + alias, expected ?: encoder.encodeToString(publicKey.encoded))
    }

    /** Never replaces missing keys. A legacy private key can only be imported against its pinned public key. */
    fun loadOrCreateParentIdentity(ctx: Context, legacyPrivate: String?, expectedPublic: String?): SigningIdentity = synchronized(identityLock) {
        identityOperation {
            val expected = expectedPublic?.takeIf { it.isNotEmpty() }
            val legacy = legacyPrivate?.takeIf { it.isNotEmpty() }
            require(expected == null || validPublicKey(expected)) { "Invalid pinned parent public key" }
            val marker = identityMarker(ctx)
            val record = if (markerExists(marker)) marker.openRead().use { it.readBytes().toString(Charsets.UTF_8) }.split('\n') else null
            check(record == null || record.size == 2 && parentAliasPattern.matches(record[0])) { "Invalid parent identity record" }
            val alias = record?.get(0) ?: PARENT_ALIAS
            val recorded = record?.get(1)
            check(recorded == null || recorded == PENDING_IDENTITY || validPublicKey(recorded)) { "Invalid parent identity marker" }
            val store = parentStore()
            if (!store.containsAlias(alias)) {
                if (legacy != null) {
                    require(expected != null && !legacy.startsWith("android-keystore:")) { "Legacy migration requires the original private/public key pair" }
                    val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(legacy)))
                    val pinnedKey = publicKey(expected)
                    if (recorded != null && recorded != PENDING_IDENTITY) requirePublicMatch(recorded, pinnedKey)
                    verifyPossession(privateKey, pinnedKey)
                    val certificate = legacyCertificate(privateKey, pinnedKey)
                    recordIdentity(marker, "$alias\n$expected")
                    store.setEntry(alias, KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
                        KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
                } else {
                    check(expected == null && recorded == null) { "Required parent signing key is missing" }
                    // Record before creation so an interrupted initialization cannot silently generate a new identity.
                    recordIdentity(marker, "$alias\n$PENDING_IDENTITY")
                    generateParentKey(alias)
                }
            }
            val pinned = recorded?.takeIf { it != PENDING_IDENTITY }
            val identity = storedParentIdentity(store, alias, expected ?: pinned)
            if (pinned != null) requirePublicMatch(pinned, publicKey(identity.publicKey))
            if (recorded != identity.publicKey) recordIdentity(marker, "$alias\n${identity.publicKey}")
            identity
        }
    }

    /** Stage without changing active trust; the caller journals its family transaction before activation. */
    fun stageFreshParentIdentity(ctx: Context): SigningIdentity = synchronized(identityLock) {
        identityOperation {
            val alias="$PARENT_ALIAS.${UUID.randomUUID()}"
            val store=parentStore();check(!store.containsAlias(alias))
            try {generateParentKey(alias);storedParentIdentity(store,alias,null)}
            catch(e:Exception){try{store.deleteEntry(alias)}catch(cleanup:Exception){e.addSuppressed(cleanup)};throw e}
        }
    }
    fun activateParentIdentity(ctx: Context, identity: SigningIdentity) = synchronized(identityLock) {
        identityOperation {
            require(identity.privateKey.startsWith(KEYSTORE_REFERENCE_PREFIX))
            val alias=identity.privateKey.removePrefix(KEYSTORE_REFERENCE_PREFIX)
            require(parentAliasPattern.matches(alias))
            storedParentIdentity(parentStore(),alias,identity.publicKey)
            recordIdentity(identityMarker(ctx),"$alias\n${identity.publicKey}")
        }
    }

    /** Explicit compromise recovery: verify a staged key before switching the durable active identity. */
    fun createFreshParentIdentity(ctx: Context): SigningIdentity = synchronized(identityLock) {
        identityOperation {
            val store = parentStore()
            val marker = identityMarker(ctx)
            val previous = if (markerExists(marker)) marker.openRead().use { it.readBytes().toString(Charsets.UTF_8) } else null
            val alias = "$PARENT_ALIAS.${UUID.randomUUID()}"
            check(!store.containsAlias(alias))
            try {
                generateParentKey(alias)
                val fresh = storedParentIdentity(store, alias, null)
                recordIdentity(marker, "$alias\n${fresh.publicKey}")
                fresh
            } catch (e: Exception) {
                try {
                    if (previous == null) marker.delete()
                    else recordIdentity(marker, previous)
                    store.deleteEntry(alias)
                } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                throw e
            }
        }
    }

    /** Only for an explicit local reset; ordinary load/sign failures must retain the identity. */
    fun deleteParentIdentity(ctx: Context) = synchronized(identityLock) {
        identityOperation {
            val store = parentStore()
            store.aliases().toList().filter { parentAliasPattern.matches(it) }.forEach { store.deleteEntry(it) }
            val marker = identityMarker(ctx)
            marker.delete()
            File(marker.baseFile.path + ".new").let { check(!it.exists() || it.delete()) }
            check(!markerExists(marker)) { "Parent identity marker could not be deleted" }
        }
    }

    /** Keystore references sign in place; PKCS8 remains accepted for legacy callers and JVM vectors. */
    fun signBytes(bytes: ByteArray, privateKeyRef: String): ByteArray = synchronized(identityLock) {
        identityOperation {
            val key = if (privateKeyRef.startsWith(KEYSTORE_REFERENCE_PREFIX)) {
                val alias = privateKeyRef.removePrefix(KEYSTORE_REFERENCE_PREFIX)
                require(parentAliasPattern.matches(alias)) { "Unknown parent signing key reference" }
                (parentStore().getKey(alias, null) as? PrivateKey ?: throw SecurityException("Required parent signing key is missing"))
                    .also { check(it.encoded == null) { "Parent signing key is exportable" } }
            } else KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privateKeyRef)))
            Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }
        }
    }

    /** Minimal self-signed X.509 carrier required by AndroidKeyStore private-key import, built with public JCA APIs. */
    private fun legacyCertificate(privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
        fun der(tag: Int, content: ByteArray): ByteArray {
            require(content.size <= 65_535)
            val size = content.size
            val length = when {
                size < 128 -> byteArrayOf(size.toByte())
                size <= 255 -> byteArrayOf(0x81.toByte(), size.toByte())
                else -> byteArrayOf(0x82.toByte(), (size ushr 8).toByte(), size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + content
        }
        val algorithm = der(0x30, der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)))
        val name = X500Principal("CN=IG Guard Parent").encoded
        val validity = der(0x30, der(0x17, "200101000000Z".toByteArray(Charsets.US_ASCII)) +
            der(0x18, "20991231235959Z".toByteArray(Charsets.US_ASCII)))
        val tbs = der(0x30, der(0x02, byteArrayOf(1)) + algorithm + name + validity + name + publicKey.encoded)
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(tbs); sign() }
        val encoded = der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signature))
        return (CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate).also {
            it.checkValidity()
            it.verify(publicKey)
        }
    }
    private fun publicKey(value: String) = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoder.decode(value)))
    fun validPublicKey(value: String): Boolean = try { value.length in 80..256 && publicKey(value).algorithm == "EC" } catch (_: Exception) { false }
    fun validToken(value: String): Boolean = tokenPattern.matches(value)
    fun validCode(value: String): Boolean = codePattern.matches(value)
    fun randomUnlockCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        return buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
    fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    fun decode(value: String): String = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(decoder.decode(value))).toString()
    fun safetyCode(token: String, verifier: String, publicKey: String): String =
        MessageDigest.getInstance("SHA-256").digest("$token\n$verifier\n$publicKey".toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02X".format(it.toInt() and 255) }.chunked(4).joinToString("-")
    fun signCommand(payload: JSONObject, privateKey: String): JSONObject {
        val text = payload.toString()
        val signature = signBytes(text.toByteArray(Charsets.UTF_8), privateKey)
        return JSONObject().put("payload", text).put("signature", encoder.encodeToString(signature))
    }
    fun readCommand(envelope: JSONObject, parentPublicKey: String, scope: String, now: Long): JSONObject? = try {
        val text = envelope.getString("payload")
        if (text.length > 16_384 || !validPublicKey(parentPublicKey)) null
        else {
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey(parentPublicKey)); update(text.toByteArray(Charsets.UTF_8))
            }
            if (!signature.verify(decoder.decode(envelope.getString("signature")))) null
            else JSONObject(text).takeIf { validCommand(it, scope, now) }
        }
    } catch (_: Exception) { null }
    private fun validCommand(command: JSONObject, scope: String, now: Long): Boolean {
        val type = command.optString("type")
        if (type !in controlTypes || command.optString("scope") != scope) return false
        val id = command.optString("command_id")
        if (try { UUID.fromString(id).toString() != id } catch (_: Exception) { true }) return false
        val timestamp = command.optLong("ts_ms")
        val lifetime = if (type == "remote_reset_child") 20 * 60_000L else 24 * 60 * 60_000L
        if (timestamp <= 0 || timestamp > now + 120_000L || timestamp < now - lifetime) return false
        return when (type) {
            "capture_policy" -> command.optInt("schema_version")==2 && command.optString("mode")=="full_visible_conversation" &&
                try { UUID.fromString(command.getString("policy_id")).toString()==command.getString("policy_id") } catch(_:Exception){false}
            "scores_mode_policy" -> command.opt("enabled") is Boolean &&
                try { UUID.fromString(command.getString("policy_id")).toString()==command.getString("policy_id") } catch(_:Exception){false}
            "unlock_code" -> validCode(command.optString("code")) && command.optLong("exp") in (now + 1)..(timestamp + 15 * 60_000L)
            "alert_receipt" -> command.optString("event_id").matches(Regex("[A-Za-z0-9:_-]{1,160}")) && command.optString("outcome") in setOf("stored","discarded")
            "key_rotated" -> validToken(command.optString("new_token")) && isVerifier(command.optString("new_pin"))
            else -> true
        }
    }
}
