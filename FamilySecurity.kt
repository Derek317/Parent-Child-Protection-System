package app.family.igguard

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Parent authority is separate from the encryption token shared with a child. */
object FamilySecurity {
    val controlTypes = setOf("sync_trusted", "unlock_code", "key_rotated", "remote_reset_child", "alert_receipt")
    private const val ITERATIONS = 600_000
    private val encoder = Base64.getUrlEncoder()
    private val decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()
    private val tokenPattern = Regex("[a-zA-Z0-9-]{8,64}")
    private val codePattern = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}")
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
    fun newSigningIdentity(): SigningIdentity {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return SigningIdentity(encoder.encodeToString(keys.private.encoded), encoder.encodeToString(keys.public.encoded))
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
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privateKey))))
            update(text.toByteArray(Charsets.UTF_8))
        }.sign()
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
            "unlock_code" -> validCode(command.optString("code")) && command.optLong("exp") in (now + 1)..(timestamp + 15 * 60_000L)
            "alert_receipt" -> command.optString("event_id").matches(Regex("[A-Za-z0-9:_-]{1,160}")) && command.optString("outcome") in setOf("stored","discarded")
            "key_rotated" -> validToken(command.optString("new_token")) && isVerifier(command.optString("new_pin"))
            else -> true
        }
    }
}
