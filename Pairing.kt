package app.family.igguard

import org.json.JSONObject
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * QR pairing state machines (design m2-and-multichild rev. 3, sections 2.2-2.6). Not wired into any flow yet.
 * No transport, UI, storage or global state: the caller encrypts every emitted record with
 * GuardEngine.encryptPayload(rendezvous.token, record.toString()) and passes the decrypted records back in.
 *
 * Key texts: exactly one canonical form is used everywhere (HKDF info, digits/pictures transcript, child-record prefix, hashes):
 * padded base64url SPKI as PairingCrypto.publicKeyText produces it. Received key texts (P in the QR, k/ec in M1, ep in M2)
 * that are not canonical are refused; keys produced on this phone (the parent's own P, the child's own K) are normalised once.
 */
object Pairing {
    const val MAX_DIGIT_TRIES = 3
    /** The parent must accept within this time of sending M2; the child waits this long for M3 after its own confirm. */
    const val CONFIRM_WINDOW_MS = 10 * 60_000L
    /** Clock difference tolerated between the phones, as FamilySecurity.validCommand / readChildRecord already assume. */
    const val CLOCK_SKEW_MS = 120_000L
    private const val MODEL_MAX = 64
    private val encoder = Base64.getUrlEncoder()

    enum class CancelReason { EXPIRED, SECOND_PHONE, WRONG_DIGITS, USER_CANCEL, BAD_MESSAGE, KEY_UNAVAILABLE }

    /** What the transport needs for a room: [token] is the AES-GCM key input (GuardEngine.encryptPayload), [scope] the
     * channel scope, [room] the relay room id, [childCapability] the v1 child credential. Holds a secret: never log it. */
    data class Rendezvous(val token: String, val scope: String, val room: String, val childCapability: String) {
        override fun toString() = "Rendezvous(scope=$scope, room=$room)"
    }
    /** The same derivations the app uses for a family token: used with S for the pairing room and with T1 for the new family room. */
    fun rendezvous(token: String, parentKey: String): Rendezvous {
        val scope = GuardEngine.getChannelTopicForToken(token)
        return Rendezvous(token, scope, FamilyRelay.room(parentKey, scope), FamilyRelay.childCapability(token))
    }

    /** The canonical text of a valid P-256 key text (padded or not), or null: the same form signed child records hash. */
    fun canonicalKey(text: String?): String? = text?.let { FamilySecurity.canonicalKeyText(it) }
    fun isCanonicalKey(text: String?): Boolean = text != null && canonicalKey(text) == text
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun sameText(a: String?, b: String): Boolean = a != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    internal fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** A signed parent command as a relay record, the shape postEncryptedToChannel's control branch builds. */
    fun commandRecord(envelope: JSONObject, commandId: String): JSONObject =
        JSONObject().put("type", "alert").put("event_id", "control:$commandId").put("relay_control", true).put("envelope", envelope)

    /** The verified payload of a parent command record of [type], or null (signature by [parentKey], scope, time, fields, wrapper id). */
    internal fun readCommandRecord(record: JSONObject, type: String, parentKey: String, scope: String, now: Long): JSONObject? {
        if (record.opt("type") != "alert" || record.opt("relay_control") != true) return null
        val envelope = record.opt("envelope") as? JSONObject ?: return null
        val command = FamilySecurity.readCommand(envelope, parentKey, scope, now) ?: return null
        return command.takeIf { it.opt("type") == type && record.opt("event_id") == "control:" + it.opt("command_id") }
    }

    internal fun validModel(model: Any?): Boolean = model is String && model.length in 1..MODEL_MAX

    /** The type named inside a room record (child_signed text or a command envelope's payload), UNVERIFIED: for routing only. */
    internal fun signedType(record: JSONObject): String? = try {
        val text = record.opt("child_signed") as? String ?: (record.opt("envelope") as? JSONObject)?.opt("payload") as? String
        text?.let { JSONObject(it).opt("type") as? String }
    } catch (_: Exception) { null }
    /** M1, M2 or M3. Anything else in a pairing room (a parent command queued in the new family room) is not the pairing's. */
    internal fun isPairingRecord(record: JSONObject): Boolean = signedType(record) in setOf("pair_hello", "pair_reply", "pair_complete")
}

sealed class ParentPairingState {
    object Idle : ParentPairingState()
    data class ShowingQr(val expiresAt: Long) : ParentPairingState()
    data class EnterDigits(val triesLeft: Int, val pictures: String, val childModel: String) : ParentPairingState()
    data class ReadyToAccept(val pictures: String, val childModel: String) : ParentPairingState()
    object Accepted : ParentPairingState()
    data class Cancelled(val reason: Pairing.CancelReason) : ParentPairingState()
}

/** One per QR. Single use: after Accepted or Cancelled every input is refused and [start] throws. */
class ParentPairing(
    /** Signs a command payload into {payload, signature}: FamilySecurity.signCommand(it, parentIdentity.privateKey). */
    private val sign: (JSONObject) -> JSONObject,
    /** GuardEngine's 16-entry EMOJI_PALETTE. */
    private val palette: List<String>,
    private val newSecret: () -> String = PairingCrypto::newPairingSecret,
    private val newEphemeral: () -> KeyPair = PairingCrypto::newEphemeralKeyPair,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Start(val qrText: String, val rendezvous: Pairing.Rendezvous, val expiresAt: Long) {
        override fun toString() = "Start(expiresAt=$expiresAt)"
    }
    /** [childKey] to pin, [token] = T1 to store, [family] the new room, [complete] = M3 to send there (encrypted under T1). */
    data class Result(val childKey: String, val token: String, val pictures: String, val childModel: String,
                      val family: Pairing.Rendezvous, val complete: JSONObject) {
        override fun toString() = "Result(childKey=$childKey)"
    }

    var state: ParentPairingState = ParentPairingState.Idle
        private set
    private var parentKey = ""
    private var scope = ""
    private var startedAt = 0L
    private var expiresAt = 0L
    private var secret: String? = null
    private var token: String? = null
    private var digits: String? = null
    private var reply: JSONObject? = null
    private var replyAt = 0L
    private var childKey = ""
    private var childModel = ""
    private var pictures = ""
    /** "K\nEc" of every verified M1 for this QR. */
    private val hellos = HashSet<String>()
    internal val holdsSecrets get() = secret != null || token != null || digits != null
    private val terminal get() = state == ParentPairingState.Accepted || state is ParentPairingState.Cancelled

    fun start(parentKey: String, now: Long): Start {
        check(state == ParentPairingState.Idle) { "A pairing is single use" }
        val p = requireNotNull(Pairing.canonicalKey(parentKey)) { "Invalid parent key" }
        val s = newSecret()
        val exp = now + PairingCrypto.QR_LIFETIME_MS
        val qr = PairingCrypto.buildPairingQr(s, p, exp)
        val rendezvous = Pairing.rendezvous(s, p)
        this.parentKey = p; secret = s; scope = rendezvous.scope; startedAt = now; expiresAt = exp
        state = ParentPairingState.ShowingQr(exp)
        return Start(qr, rendezvous, exp)
    }

    /** M1 in, M2 out (or null). Exactly-one rule: a second distinct (K, Ec) at any time before accept cancels;
     * a resend with the same K and Ec returns the same M2. Any M1 that fails verification cancels (BAD_MESSAGE). */
    fun onHello(record: JSONObject, now: Long): JSONObject? {
        expire(now)
        if (terminal || state == ParentPairingState.Idle) return null
        val hello = verifyHello(record, now) ?: return cancelled(Pairing.CancelReason.BAD_MESSAGE)
        val id = hello.getString("k") + "\n" + hello.getString("ec")
        if (id in hellos) return reply
        if (hellos.isNotEmpty()) return cancelled(Pairing.CancelReason.SECOND_PHONE)
        hellos += id
        val k = hello.getString("k"); val ec = hello.getString("ec")
        val ephemeral = newEphemeral()
        val ep = PairingCrypto.publicKeyText(ephemeral.public)
        val t1 = try { PairingCrypto.familyToken(ephemeral, secret!!, parentKey, k, ep, ec) } catch (_: IllegalArgumentException) {
            return cancelled(Pairing.CancelReason.BAD_MESSAGE)
        }
        secret = null // only the scope is needed from here on
        token = t1; childKey = k; childModel = hello.getString("model")
        digits = PairingCrypto.pairingDigits(parentKey, k, ep, ec, t1)
        pictures = PairingCrypto.pairingPictures(parentKey, k, ep, ec, t1, palette)
        val commandId = newId()
        val payload = JSONObject().put("type", "pair_reply").put("scope", scope).put("command_id", commandId).put("ts_ms", now)
            .put("k_hash", Pairing.sha256Hex(k)).put("ec_hash", Pairing.sha256Hex(ec)).put("ep", ep)
        reply = Pairing.commandRecord(sign(payload), commandId)
        replyAt = now
        state = ParentPairingState.EnterDigits(Pairing.MAX_DIGIT_TRIES, pictures, childModel)
        return reply
    }

    private fun verifyHello(wrapper: JSONObject, now: Long): JSONObject? {
        if (wrapper.opt("type") != "alert") return null
        val record = try { JSONObject(wrapper.opt("child_signed") as? String ?: return null) } catch (_: Exception) { return null }
        val k = record.opt("k") as? String
        if (!Pairing.isCanonicalKey(k) || !Pairing.isCanonicalKey(record.opt("ec") as? String) || k == parentKey) return null
        val read = FamilySecurity.readChildRecord(wrapper, k!!, parentKey, now) ?: return null
        return read.record.takeIf {
            // Own time window: readChildRecord allows records dated up to 24 h ahead (RiskLimits.REPORT_FUTURE_MS).
            read.type == "pair_hello" && it.opt("scope") == scope && read.timeMs >= startedAt - Pairing.CLOCK_SKEW_MS &&
                read.timeMs <= now + Pairing.CLOCK_SKEW_MS && Pairing.validModel(it.opt("model"))
        }
    }

    /** The 6 digits typed on the parent, compared in constant time. 3 wrong entries cancel. Only in EnterDigits. */
    fun digitsEntered(text: String): Boolean {
        val current = state as? ParentPairingState.EnterDigits ?: return false
        if (Pairing.sameText(digits, text)) {
            state = ParentPairingState.ReadyToAccept(current.pictures, current.childModel)
            return true
        }
        if (current.triesLeft <= 1) cancelled(Pairing.CancelReason.WRONG_DIGITS)
        else state = current.copy(triesLeft = current.triesLeft - 1)
        return false
    }

    /** Only after correct digits and before the deadline. [passwordVerifier] travels only inside M3. */
    fun accept(passwordVerifier: String, now: Long): Result? {
        expire(now)
        if (state !is ParentPairingState.ReadyToAccept) return null
        require(FamilySecurity.isVerifier(passwordVerifier)) { "Invalid password verifier" }
        val t1 = token!!
        val family = Pairing.rendezvous(t1, parentKey)
        val commandId = newId()
        val payload = JSONObject().put("type", "pair_complete").put("scope", family.scope).put("command_id", commandId).put("ts_ms", now)
            .put("new_pin", passwordVerifier).put("k_hash", Pairing.sha256Hex(childKey))
        val result = Result(childKey, t1, pictures, childModel, family, Pairing.commandRecord(sign(payload), commandId))
        state = ParentPairingState.Accepted
        clear()
        return result
    }

    /** QR shown: expires at exp. After M2: the parent must accept within CONFIRM_WINDOW_MS of sending it. */
    fun expire(now: Long) {
        val deadline = when (state) {
            is ParentPairingState.ShowingQr -> expiresAt
            is ParentPairingState.EnterDigits, is ParentPairingState.ReadyToAccept -> replyAt + Pairing.CONFIRM_WINDOW_MS
            else -> return
        }
        if (now >= deadline) cancelled(Pairing.CancelReason.EXPIRED)
    }

    fun cancel() { if (!terminal) cancelled(Pairing.CancelReason.USER_CANCEL) }

    private fun cancelled(reason: Pairing.CancelReason): JSONObject? {
        state = ParentPairingState.Cancelled(reason)
        clear()
        return null
    }
    private fun clear() { secret = null; token = null; digits = null; reply = null }
}

sealed class ChildPairingState {
    object Idle : ChildPairingState()
    object AwaitingReply : ChildPairingState()
    data class ShowingDigits(val digits: String, val pictures: String) : ChildPairingState()
    object AwaitingComplete : ChildPairingState()
    object Paired : ChildPairingState()
    data class Cancelled(val reason: Pairing.CancelReason) : ChildPairingState()
}

/** One per scan. Single use. Any failure or timeout cancels and deletes the child signing key through [deleteKey]. */
class ChildPairing(
    /** FamilySecurity.createChildSigningKey(ctx). */
    private val createKey: () -> FamilySecurity.ChildSigningKey,
    /** FamilySecurity.deleteChildSigningKey(ctx, alias). */
    private val deleteKey: (String) -> Unit,
    private val palette: List<String>,
    private val sign: (FamilySecurity.ChildSigningKey, ByteArray) -> ByteArray = { key, bytes -> FamilySecurity.signChildBytes(bytes, key.alias) },
    private val newEphemeral: () -> KeyPair = PairingCrypto::newEphemeralKeyPair,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Start(val hello: JSONObject, val rendezvous: Pairing.Rendezvous) {
        override fun toString() = "Start(rendezvous=$rendezvous)"
    }
    /** What to persist only now: [token] = T1, the pinned [parentKey], the verifier, and the key alias / public key. */
    data class Result(val token: String, val parentKey: String, val passwordVerifier: String, val childKeyAlias: String, val childKey: String) {
        override fun toString() = "Result(childKeyAlias=$childKeyAlias)"
    }

    var state: ChildPairingState = ChildPairingState.Idle
        private set
    private var key: FamilySecurity.ChildSigningKey? = null
    private var k = ""
    private var ec = ""
    private var parentKey = ""
    private var scope = ""
    private var qrExpiresAt = 0L
    private var secret: String? = null
    private var ephemeral: KeyPair? = null
    private var token: String? = null
    private var replyPayload: String? = null
    private var replyAt = 0L
    private var confirmedAt = 0L
    internal val holdsSecrets get() = secret != null || ephemeral != null || token != null
    /** K's alias while this machine holds K (null before start and after a cancel). */
    internal val keyAlias get() = key?.alias
    private val terminal get() = state == ChildPairingState.Paired || state is ChildPairingState.Cancelled

    /** Parses the QR, creates K and Ec, returns M1 and the pairing room; null if refused (see state). */
    fun start(qrText: String, phoneModel: String, now: Long): Start? {
        check(state == ChildPairingState.Idle) { "A pairing is single use" }
        require(Pairing.validModel(phoneModel)) { "Invalid phone model" }
        val qr = PairingCrypto.parsePairingQr(qrText, now)
        if (qr == null || !Pairing.isCanonicalKey(qr.parentKey)) {
            val exp = qrText.substringAfterLast('.').toLongOrNull()
            val expired = qr == null && exp != null && exp <= now && PairingCrypto.parsePairingQr(qrText, exp - 1) != null
            return cancelled(if (expired) Pairing.CancelReason.EXPIRED else Pairing.CancelReason.BAD_MESSAGE)
        }
        try {
            val created = createKey()
            key = created
            k = requireNotNull(Pairing.canonicalKey(created.publicKey))
            val ownEphemeral = newEphemeral()
            ephemeral = ownEphemeral
            ec = PairingCrypto.publicKeyText(ownEphemeral.public)
            parentKey = qr.parentKey; secret = qr.secret; qrExpiresAt = qr.expiresAt
            val rendezvous = Pairing.rendezvous(qr.secret, qr.parentKey)
            scope = rendezvous.scope
            val commandId = newId()
            val record = JSONObject().put("type", "pair_hello").put("scope", scope).put("command_id", commandId).put("ts_ms", now)
                .put("k", k).put("ec", ec).put("model", phoneModel).toString()
            val signature = Pairing.b64(sign(created, FamilySecurity.childRecordSignedBytes(record, parentKey, k)))
            val hello = JSONObject().put("type", "alert").put("event_id", "control:$commandId").put("child_signed", record).put("child_sig", signature)
            state = ChildPairingState.AwaitingReply
            return Start(hello, rendezvous)
        } catch (_: Exception) {
            return cancelled(Pairing.CancelReason.KEY_UNAVAILABLE)
        }
    }

    /** M2 in. True when the digits and pictures are ready to show. A byte-identical resend is ignored; anything else out of turn cancels. */
    fun onReply(record: JSONObject, now: Long): Boolean {
        expire(now)
        if (terminal || state == ChildPairingState.Idle) return false
        if (state != ChildPairingState.AwaitingReply) {
            val resend = ((record.opt("envelope") as? JSONObject)?.opt("payload") as? String)?.let { it == replyPayload } == true
            if (!resend) cancelled(Pairing.CancelReason.BAD_MESSAGE)
            return false
        }
        val command = Pairing.readCommandRecord(record, "pair_reply", parentKey, scope, now)
        val ep = command?.opt("ep") as? String
        if (command == null || !Pairing.isCanonicalKey(ep) || !Pairing.sameText(command.opt("k_hash") as? String, Pairing.sha256Hex(k)) ||
            !Pairing.sameText(command.opt("ec_hash") as? String, Pairing.sha256Hex(ec))) { cancelled(Pairing.CancelReason.BAD_MESSAGE); return false }
        val t1 = try { PairingCrypto.familyToken(ephemeral!!, secret!!, parentKey, k, ep!!, ec) } catch (_: IllegalArgumentException) {
            cancelled(Pairing.CancelReason.BAD_MESSAGE); return false
        }
        ephemeral = null; secret = null
        token = t1
        replyPayload = (record.getJSONObject("envelope")).getString("payload")
        replyAt = now
        state = ChildPairingState.ShowingDigits(PairingCrypto.pairingDigits(parentKey, k, ep, ec, t1), PairingCrypto.pairingPictures(parentKey, k, ep, ec, t1, palette))
        return true
    }

    /** The child's own consent press. Returns the new family room to connect to and read M3 from; nothing is persisted yet. */
    fun confirm(now: Long): Pairing.Rendezvous? {
        expire(now)
        if (state !is ChildPairingState.ShowingDigits) return null
        confirmedAt = now
        state = ChildPairingState.AwaitingComplete
        return Pairing.rendezvous(token!!, parentKey)
    }

    /** M3 in (from the new family room). Only after [confirm]; returns what to persist, or null and cancels. */
    fun onComplete(record: JSONObject, now: Long): Result? {
        expire(now)
        if (terminal || state == ChildPairingState.Idle) return null
        if (state != ChildPairingState.AwaitingComplete) return cancelled(Pairing.CancelReason.BAD_MESSAGE)
        val t1 = token!!
        val command = Pairing.readCommandRecord(record, "pair_complete", parentKey, GuardEngine.getChannelTopicForToken(t1), now)
        if (command == null || !Pairing.sameText(command.opt("k_hash") as? String, Pairing.sha256Hex(k))) return cancelled(Pairing.CancelReason.BAD_MESSAGE)
        val result = Result(t1, parentKey, command.getString("new_pin"), key!!.alias, k)
        state = ChildPairingState.Paired
        clear()
        return result
    }

    /** Waiting for M2: until QR expiry + clock skew. Digits shown: CONFIRM_WINDOW_MS after M2. Confirmed: CONFIRM_WINDOW_MS for M3. */
    fun expire(now: Long) {
        val deadline = when (state) {
            ChildPairingState.AwaitingReply -> qrExpiresAt + Pairing.CLOCK_SKEW_MS
            is ChildPairingState.ShowingDigits -> replyAt + Pairing.CONFIRM_WINDOW_MS
            ChildPairingState.AwaitingComplete -> confirmedAt + Pairing.CONFIRM_WINDOW_MS
            else -> return
        }
        if (now >= deadline) cancelled(Pairing.CancelReason.EXPIRED)
    }

    fun cancel() { if (!terminal) cancelled(Pairing.CancelReason.USER_CANCEL) }

    private fun cancelled(reason: Pairing.CancelReason): Nothing? {
        state = ChildPairingState.Cancelled(reason)
        clear()
        val created = key
        key = null
        if (created != null) deleteKey(created.alias)
        return null
    }
    private fun clear() { secret = null; ephemeral = null; token = null; replyPayload = null }
}
