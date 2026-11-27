package app.family.igguard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import java.security.ProviderException
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
    val controlTypes = setOf("sync_trusted", "unlock_code", "key_rotated", "remote_reset_child", "alert_receipt", "capture_policy", "scores_mode_policy", "import_request", "pair_reply", "pair_complete")
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

    /** The pre-update software identity, accepted only if its private key really matches the public key the child trusts.
     * Used when the Keystore import fails, so reception and signing keep working exactly as before the update. */
    fun verifiedLegacyIdentity(legacyPrivate: String, expectedPublic: String): SigningIdentity =
        identityOperation {
            require(validPublicKey(expectedPublic) && !legacyPrivate.startsWith(KEYSTORE_REFERENCE_PREFIX)) { "Invalid legacy parent identity" }
            verifyPossession(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(legacyPrivate))), publicKey(expectedPublic))
            SigningIdentity(legacyPrivate, expectedPublic)
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
        if (text.length > 16_384 || !verifySigned(text.toByteArray(Charsets.UTF_8), envelope.getString("signature"), parentPublicKey)) null
        else JSONObject(text).takeIf { validCommand(it, scope, now) }
    } catch (_: Exception) { null }
    const val EARLY_COMMAND_BOUND_MS = 24 * 60 * 60_000L
    fun commandAhead(envelope: JSONObject, parentPublicKey: String, scope: String, now: Long): Boolean = try {
        val ts = JSONObject(envelope.getString("payload")).optLong("ts_ms")
        ts > now + 120_000L && ts <= now + EARLY_COMMAND_BOUND_MS && readCommand(envelope, parentPublicKey, scope, ts) != null
    } catch (_: Exception) { false }
    fun signedBy(envelope: JSONObject, publicKey: String): Boolean =
        try { verifySigned(envelope.getString("payload").toByteArray(Charsets.UTF_8), envelope.getString("signature"), publicKey) } catch (_: Exception) { false }
    /** ECDSA-P256-SHA256 over [signed] with the base64url DER [signature]; false for an invalid key text. May throw on malformed input. */
    private fun verifySigned(signed: ByteArray, signature: String, publicKeyText: String): Boolean =
        validPublicKey(publicKeyText) && Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(publicKeyText)); update(signed); verify(decoder.decode(signature))
        }
    private val hex64 = Regex("[0-9a-f]{64}")
    /** A command the parent acts on as soon as the relay accepts it must reach a child offline for as long as it can still be
     *  delivered: up to 3 days in the parent's outbox (FamilyEvidence PENDING_TTL), then 7 days at the relay (relay/src/worker.js TTL).
     *  The child keeps such ids as long (GuardEngine control_state_json "relayed_ids"). */
    const val RELAYED_COMMAND_LIFETIME_MS = (3 + 7) * 24 * 60 * 60_000L
    val relayedCommandTypes = setOf("key_rotated", "remote_reset_child")
    private fun validCommand(command: JSONObject, scope: String, now: Long): Boolean {
        val type = command.optString("type")
        if (type !in controlTypes || command.optString("scope") != scope) return false
        val id = command.optString("command_id")
        if (try { UUID.fromString(id).toString() != id } catch (_: Exception) { true }) return false
        val timestamp = command.optLong("ts_ms")
        // Pairing messages only matter while a pairing screen is open (QR 3 min, child waits at most 10 min for pair_complete).
        val lifetime = if (type in relayedCommandTypes) RELAYED_COMMAND_LIFETIME_MS
            else if (type in setOf("pair_reply", "pair_complete")) 20 * 60_000L else 24 * 60 * 60_000L
        if (timestamp <= 0 || timestamp > now + 120_000L || timestamp < now - lifetime) return false
        return when (type) {
            "capture_policy" -> command.optInt("schema_version")==2 && command.optString("mode")=="full_visible_conversation" &&
                try { UUID.fromString(command.getString("policy_id")).toString()==command.getString("policy_id") } catch(_:Exception){false}
            "scores_mode_policy" -> command.opt("enabled") is Boolean && (!command.has("method") || ScoresMethod.valid(command.opt("method"))) &&
                try { UUID.fromString(command.getString("policy_id")).toString()==command.getString("policy_id") } catch(_:Exception){false}
            "unlock_code" -> validCode(command.optString("code")) && command.optLong("exp") in (now + 1)..(timestamp + 15 * 60_000L)
            "alert_receipt" -> command.optString("event_id").matches(Regex("[A-Za-z0-9:_-]{1,160}")) && command.optString("outcome") in setOf("stored","discarded")
            "key_rotated" -> validToken(command.optString("new_token")) && isVerifier(command.optString("new_pin"))
            // k_hash / ec_hash: lowercase hex SHA-256 of the UTF-8 text of the child's K and Ec; ep: the parent's ephemeral key text.
            "pair_reply" -> hex64.matches(command.opt("k_hash") as? String ?: "") && hex64.matches(command.opt("ec_hash") as? String ?: "") &&
                PairingCrypto.validP256Key(command.opt("ep") as? String)
            "pair_complete" -> isVerifier(command.opt("new_pin") as? String ?: "") && hex64.matches(command.opt("k_hash") as? String ?: "")
            else -> true
        }
    }

    // ---------------- child signing key (audit M2) ----------------
    private const val CHILD_ALIAS = "ig_guard.child_signing.ec.v1"
    private val childAliasPattern = Regex(Regex.escape(CHILD_ALIAS) + "\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    /** A child phone's Keystore signing key. [publicKey] is base64url SPKI text, the same form as a parent public key. */
    data class ChildSigningKey(val alias: String, val publicKey: String)
    private inline fun <T> childKeyOperation(action: () -> T): T = try { action() }
        catch (e: Exception) { throw SecurityException("Child signing key is unavailable", e) }
    private fun childMarker(ctx: Context, alias: String) = AtomicFile(File(ctx.noBackupFilesDir, "$alias.initialized"))
    private fun generateChildKey(ctx: Context, store: KeyStore, alias: String) {
        fun generate(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
            if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
            initialize(spec.build())
        }.generateKeyPair()
        if (Build.VERSION.SDK_INT < 28 || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) { generate(false); return }
        // StrongBoxUnavailableException is a ProviderException; any StrongBox refusal falls back to the TEE/software Keystore.
        try { generate(true) } catch (_: ProviderException) { if (store.containsAlias(alias)) store.deleteEntry(alias); generate(false) }
    }
    private fun storedChildKey(store: KeyStore, alias: String, expected: String?): ChildSigningKey {
        val key = store.getKey(alias, null) as? PrivateKey ?: throw SecurityException("Child signing key is missing")
        check(key.encoded == null) { "Child signing key is exportable" }
        val publicKey = store.getCertificate(alias)?.publicKey ?: throw SecurityException("Child public-key certificate is missing")
        if (expected != null) requirePublicMatch(expected, publicKey)
        verifyPossession(key, publicKey)
        return ChildSigningKey(alias, expected ?: encoder.encodeToString(publicKey.encoded))
    }

    /** A new non-exportable P-256 key under a fresh alias; a reset or new pairing makes another one and deletes the old by alias. */
    fun createChildSigningKey(ctx: Context): ChildSigningKey = synchronized(identityLock) {
        childKeyOperation {
            val alias = "$CHILD_ALIAS.${UUID.randomUUID()}"
            val store = parentStore(); check(!store.containsAlias(alias))
            val marker = childMarker(ctx, alias)
            try {
                recordIdentity(marker, "$alias\n$PENDING_IDENTITY")
                generateChildKey(ctx, store, alias)
                storedChildKey(store, alias, null).also { recordIdentity(marker, "$alias\n${it.publicKey}") }
            } catch (e: Exception) {
                try { if (store.containsAlias(alias)) store.deleteEntry(alias); marker.delete() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                throw e
            }
        }
    }
    /** Never replaces a missing key; the public key must still match the one recorded at creation. */
    fun loadChildSigningKey(ctx: Context, alias: String): ChildSigningKey = synchronized(identityLock) {
        childKeyOperation {
            require(childAliasPattern.matches(alias)) { "Unknown child signing key alias" }
            val marker = childMarker(ctx, alias)
            check(markerExists(marker)) { "Child signing key was not created on this phone" }
            val record = marker.openRead().use { it.readBytes().toString(Charsets.UTF_8) }.split('\n')
            check(record.size == 2 && record[0] == alias && (record[1] == PENDING_IDENTITY || validPublicKey(record[1]))) { "Invalid child key record" }
            val key = storedChildKey(parentStore(), alias, record[1].takeIf { it != PENDING_IDENTITY })
            if (record[1] != key.publicKey) recordIdentity(marker, "$alias\n${key.publicKey}")
            key
        }
    }
    fun deleteChildSigningKey(ctx: Context, alias: String) = synchronized(identityLock) {
        childKeyOperation {
            require(childAliasPattern.matches(alias)) { "Unknown child signing key alias" }
            val store = parentStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            val marker = childMarker(ctx, alias)
            marker.delete()
            File(marker.baseFile.path + ".new").let { check(!it.exists() || it.delete()) }
            check(!markerExists(marker)) { "Child key marker could not be deleted" }
        }
    }
    /** Every child-key alias on this phone, from the Keystore and from marker files: a crash can leave either one without the other. */
    fun childSigningKeyAliases(ctx: Context): Set<String> = synchronized(identityLock) {
        childKeyOperation {
            val markers = ctx.noBackupFilesDir.list().orEmpty().filter { ".initialized" in it }.map { it.substringBefore(".initialized") }
            (parentStore().aliases().toList() + markers).filter { childAliasPattern.matches(it) }.toSet()
        }
    }
    fun signChildBytes(bytes: ByteArray, alias: String): ByteArray = synchronized(identityLock) {
        childKeyOperation {
            require(childAliasPattern.matches(alias)) { "Unknown child signing key alias" }
            val key = parentStore().getKey(alias, null) as? PrivateKey ?: throw SecurityException("Child signing key is missing")
            check(key.encoded == null) { "Child signing key is exportable" }
            Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }
        }
    }

    // ---------------- signed child records ----------------
    /** What a child signs: UTF-8 of "igguard-child-record-v1\n" + b64url(SHA-256(P)) + "\n" + b64url(SHA-256(K)) + "\n" + R,
     * hashing the UTF-8 bytes of the P and K texts as transmitted; b64url is padded (44 characters). */
    fun childRecordSignedBytes(record: String, parentPublicKey: String, childPublicKey: String): ByteArray {
        fun hash(text: String) = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        return "igguard-child-record-v1\n${hash(parentPublicKey)}\n${hash(childPublicKey)}\n$record".toByteArray(Charsets.UTF_8)
    }
    /** The one text form of a P-256 public key that is hashed into child-record signatures: base64url WITH padding, as [encoder]
     * writes it (ChildSigningKey.publicKey, newSigningIdentity). Padded or unpadded input of the same key gives the same result;
     * null unless the text is a canonical SPKI P-256 key (PairingCrypto.p256PublicKey). */
    fun canonicalKeyText(text: String): String? = PairingCrypto.p256PublicKey(text)?.let { encoder.encodeToString(it.encoded) }

    /** A verified child record. [id] is event_id (alert, captured_message) or command_id (control records); [timeMs] is the field checked for age. */
    data class ChildRecord(val record: JSONObject, val type: String, val id: String, val timeMs: Long)
    /** Why a record whose signature DID verify was still not accepted. */
    enum class ChildRecordRejection { OUT_OF_WINDOW, UNKNOWN_TYPE, UNKNOWN_REASON, BAD_ID, ID_MISMATCH, TOO_LARGE, MALFORMED_RECORD }
    sealed class ChildRecordResult {
        data class Verified(val child: ChildRecord) : ChildRecordResult()
        /** child_signed/child_sig missing, not strings, over the size bound, undecodable, or not a valid signature under P and K. */
        object BadSignature : ChildRecordResult()
        /** Signature valid under the pinned key; the record broke a field, size or time rule. Carries no record content. */
        data class Rejected(val reason: ChildRecordRejection) : ChildRecordResult()
        /** The caller's own P or K text is not a P-256 key: nothing about the frame was checked. */
        object InvalidKey : ChildRecordResult()
    }
    private val childContentTypes = setOf("alert", "captured_message")
    private val childRecordControlTypes = setOf("capture_ack", "sync_child_handle", "scores_mode_report", "pair_hello")
    /** Every reason a 5.2 child sends in an alert: heartbeats, tamper/unlock notices, tests, and message categories. */
    internal val childAlertReasons = setOf("heartbeat_ok", "pairing_verified", "test_alert", "tamper_attempt", "unlock_used",
        "stranger_message", "sexual_or_grooming_content", "flagged_contact_follow_up")
    internal val capturedReasons = setOf("conversation_context", "stranger_message", "sexual_or_grooming_content", "flagged_contact_follow_up")
    private val childRecordId = Regex("[A-Za-z0-9:_-]{1,160}")
    private const val CHILD_RECORD_MAX = 1_048_576 // AlertFrames' transport bound: captured messages and scores reports exceed 16 KiB
    private const val CHILD_CONTROL_MAX = 16_384
    const val CONTENT_RECORD_WINDOW_MS = 8 * 86_400_000L

    /** Signature, type, id and time only; replay and ordering state stay with the caller. Null on any deviation. */
    fun readChildRecord(wrapper: JSONObject, childPublicKey: String, parentPublicKey: String, now: Long): ChildRecord? =
        (checkChildRecord(wrapper, childPublicKey, parentPublicKey, now) as? ChildRecordResult.Verified)?.child

    /** As [readChildRecord], telling a bad signature apart from a genuine record outside the rules. Sizes are bounded before the
     * hash, and the signed text is not parsed until its signature has verified. A record may be dated up to
     * RiskLimits.REPORT_FUTURE_MS (24 h) ahead of [now]. Never throws. */
    fun checkChildRecord(wrapper: JSONObject, childPublicKey: String, parentPublicKey: String, now: Long): ChildRecordResult {
        val k = canonicalKeyText(childPublicKey) ?: return ChildRecordResult.InvalidKey
        val p = canonicalKeyText(parentPublicKey) ?: return ChildRecordResult.InvalidKey
        val text = wrapper.opt("child_signed") as? String
        val signature = wrapper.opt("child_sig") as? String
        if (text == null || signature == null || text.length > CHILD_RECORD_MAX || signature.length > 128 ||
            !(try { verifySigned(childRecordSignedBytes(text, p, k), signature, k) } catch (_: Exception) { false })) return ChildRecordResult.BadSignature
        fun rejected(reason: ChildRecordRejection) = ChildRecordResult.Rejected(reason)
        val record = try { JSONObject(text) } catch (_: Exception) { return rejected(ChildRecordRejection.MALFORMED_RECORD) }
        val type = record.opt("type") as? String ?: ""
        val control = type in childRecordControlTypes
        if (!control && type !in childContentTypes) return rejected(ChildRecordRejection.UNKNOWN_TYPE)
        val id = record.opt(if (control) "command_id" else "event_id") as? String ?: ""
        if (!childRecordId.matches(id)) return rejected(ChildRecordRejection.BAD_ID)
        // Checked before the time and reason rules: a re-wrapped genuine record is reported as such, whatever else it breaks.
        if (wrapper.opt("event_id") != (if (control) "control:$id" else id)) return rejected(ChildRecordRejection.ID_MISMATCH)
        if (type != "scores_mode_report" && control && text.length > CHILD_CONTROL_MAX) return rejected(ChildRecordRejection.TOO_LARGE)
        val timeField = when (type) { "scores_mode_report" -> "sent_at_ms"; "captured_message" -> "captured_at_ms"; else -> "ts_ms" }
        val time = when (val value = record.opt(timeField)) { is Int -> value.toLong(); is Long -> value; else -> return rejected(ChildRecordRejection.MALFORMED_RECORD) }
        val maxAge = when (type) {
            "alert", "captured_message" -> CONTENT_RECORD_WINDOW_MS
            "scores_mode_report" -> RiskLimits.REPORT_HORIZON_MS
            else -> 86_400_000L
        }
        if (time <= 0 || time > now + RiskLimits.REPORT_FUTURE_MS || time < now - maxAge) return rejected(ChildRecordRejection.OUT_OF_WINDOW)
        val fieldsOk = when (type) {
            "alert" -> (record.opt("reason") as? String) in childAlertReasons
            "captured_message" -> (record.opt("reason") as? String) in capturedReasons
            "pair_hello" -> canonicalKeyText(record.opt("k") as? String ?: "") == k && PairingCrypto.validP256Key(record.opt("ec") as? String)
            else -> true
        }
        if (!fieldsOk) return rejected(if (type == "pair_hello") ChildRecordRejection.MALFORMED_RECORD else ChildRecordRejection.UNKNOWN_REASON)
        return ChildRecordResult.Verified(ChildRecord(record, type, id, time))
    }
}
