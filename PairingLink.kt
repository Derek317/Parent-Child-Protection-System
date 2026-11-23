package app.family.igguard

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Wiring of one QR pairing (design m2 rev. 3, 2.5) between a state machine of Pairing.kt, its temporary relay room and GuardEngine's
 * storage. No protocol rule lives here: the machines decide, GuardEngine stores. Main thread only; relay callbacks are posted to it.
 * Records travel encrypted under the room's token (S in the rendezvous room, T1 in the new family room), as PairingFlowTest drives them.
 */
internal interface PairingSocket {
    fun publish(id: String, wire: String, done: (Boolean) -> Unit)
    fun close()
}
internal typealias PairingOpener = (Context, CloudRelay.PairingRoute) -> PairingSocket

internal val relayPairingOpener: PairingOpener = { ctx, route ->
    val connection = CloudRelay.openPairingRoom(ctx, route)
    object : PairingSocket {
        override fun publish(id: String, wire: String, done: (Boolean) -> Unit) = connection.publish(id, wire, "control") { done(it.accepted) }
        override fun close() = connection.close()
    }
}

/** One record sent in a pairing room, kept so a reconnect sends the very same ciphertext again (the relay refuses a changed body). */
private class Outgoing(val id: String, val wire: String)
private fun outgoing(record: JSONObject, token: String) = Outgoing(record.getString("event_id") + ":0", GuardEngine.encryptPayload(token, record.toString()))

/** Parent side. [onChange] runs after every state change. */
internal class ParentPairingLink(
    private val ctx: Context,
    private val onChange: () -> Unit,
    private val open: PairingOpener = relayPairingOpener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Relay { CONNECTING, READY, TOO_OLD, REFUSED, UNREACHABLE }
    enum class Outcome { NONE, ACCEPTED, NOT_STORED }

    val machine = GuardEngine.newParentPairing(ctx)
    var relay = Relay.CONNECTING; private set
    var outcome = Outcome.NONE; private set
    private val main = Handler(Looper.getMainLooper())
    private var socket: PairingSocket? = null
    private var start: ParentPairing.Start? = null
    private var sent: Outgoing? = null
    private var retired = false
    private val ticker = object : Runnable { override fun run() { tick(); if (!ended) main.postDelayed(this, 1_000L) } }
    val ended get() = machine.state is ParentPairingState.Cancelled || outcome != Outcome.NONE
    /** The QR text, only while it may be on screen: shown, not expired, and the relay proved it supports child keys (design 4.5). */
    val qrText: String? get() = start?.qrText?.takeIf { relay == Relay.READY && machine.state is ParentPairingState.ShowingQr }
    val expiresAt: Long get() = start?.expiresAt ?: 0L

    fun begin() {
        val s = machine.start(GuardEngine.relayPublicKey(ctx), clock())
        start = s
        val token = s.rendezvous.token
        // As on the child: callbacks of a connection that is no longer this link's socket are ignored.
        var mine: PairingSocket? = null
        fun current() = mine != null && socket === mine
        mine = try {
            open(ctx, CloudRelay.PairingRoute(GuardEngine.relayPublicKey(ctx), token, { GuardEngine.relayAuthorization(ctx, token) },
                onReady = { ready -> main.post { if (current()) ready(ready) } },
                onRecord = { record -> main.post { if (current()) hello(record) } },
                onRefused = { main.post { if (current() && !ended) { relay = Relay.REFUSED; machine.cancel(); finish() } } }))
        } catch (_: Exception) { relay = Relay.UNREACHABLE; machine.cancel(); finish(); return }
        socket = mine
        main.postDelayed(ticker, 1_000L)
        onChange()
    }
    private fun ready(ready: JSONObject) {
        if (ended) return
        if (!FamilyRelay.supportsChildProof(ready)) { relay = Relay.TOO_OLD; machine.cancel(); finish(); return }
        relay = Relay.READY
        sent?.let { publish(it) }
        onChange()
    }
    private fun hello(record: JSONObject) {
        if (ended) return
        val reply = machine.onHello(record, clock())
        if (reply != null && sent == null) { sent = outgoing(reply, start!!.rendezvous.token); publish(sent!!) }
        if (ended) finish() else onChange()
    }
    private fun publish(o: Outgoing) = socket?.publish(o.id, o.wire) {}

    /** False for a wrong entry (the field is cleared by the screen); three wrong entries cancel. */
    fun enterDigits(text: String): Boolean {
        val ok = machine.digitsEntered(text)
        if (ended) finish() else onChange()
        return ok
    }

    /** Only after the typed digits matched and the parent's fresh authentication: stores the pairing (GuardEngine.completeParentPairing). */
    fun accept(): Boolean {
        val result = machine.accept(GuardEngine.parentPasswordVerifier(ctx), clock())
        if (result == null) { if (ended) finish() else onChange(); return false }
        val token = start!!.rendezvous.token
        val stored = try { GuardEngine.completeParentPairing(ctx, result, clock()) { delivered -> if (delivered) main.post { retire(token) } } } catch (_: Exception) { null }
        outcome = if (stored != null) Outcome.ACCEPTED else Outcome.NOT_STORED
        socket?.close(); socket = null
        // Nothing was stored: the rendezvous room has no further use.
        if (stored == null) retire(token)
        main.removeCallbacks(ticker)
        onChange()
        return stored != null
    }

    fun tick() {
        if (ended) return
        machine.expire(clock())
        if (ended) finish() else onChange()
    }
    fun cancel() { if (!ended) { machine.cancel(); finish() } }

    private fun finish() {
        socket?.close(); socket = null
        main.removeCallbacks(ticker)
        start?.let { retire(it.rendezvous.token) }
        onChange()
    }
    /** Retires the rendezvous room once (cancel, expiry, or after M3 was accepted by the relay). */
    private fun retire(token: String) {
        if (retired) return
        retired = true
        try { CloudRelay.retirePairingRoom(ctx, token) { _, _ -> } } catch (_: Exception) {}
    }
}

/** Child side. [onResult] stores the finished pairing (it may wait for the old family's notice: call GuardEngine off the main thread). */
internal class ChildPairingLink(
    private val ctx: Context,
    private val onChange: () -> Unit,
    private val onResult: (ChildPairing.Result) -> Unit,
    private val open: PairingOpener = relayPairingOpener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val model: String = (Build.MODEL ?: "").take(64).ifEmpty { "Android" },
) {
    private var key: FamilySecurity.ChildSigningKey? = null
    val machine = GuardEngine.newChildPairing(ctx) { key = it }
    /** The relay refused a pairing room (401/410): the pairing was stopped. */
    var refused = false; private set
    private val main = Handler(Looper.getMainLooper())
    private var socket: PairingSocket? = null
    private var hello: Outgoing? = null
    private var parentKey = ""
    private val ticker = object : Runnable { override fun run() { tick(); if (!ended) main.postDelayed(this, 1_000L) } }
    val ended get() = machine.state is ChildPairingState.Cancelled || machine.state == ChildPairingState.Paired

    /** The join press: creates K, sends M1 in the rendezvous room. False when the QR was refused (see machine.state). */
    fun join(qrText: String): Boolean {
        val s = machine.start(qrText, model, clock()) ?: run { onChange(); return false }
        parentKey = qrText.split('.')[2] // the machine accepted the QR, so P is its canonical third field
        hello = outgoing(s.hello, s.rendezvous.token)
        connect(s.rendezvous) { FamilyRelay.childAuthorization(parentKey, s.rendezvous.scope, s.rendezvous.token) }
        main.postDelayed(ticker, 1_000L)
        onChange()
        return true
    }
    private fun connect(room: Pairing.Rendezvous, authorization: () -> String) {
        socket?.close()
        // Every callback first checks that its connection is still this link's current one: a room left behind (the rendezvous
        // room after M2, which the parent retires) can never advance or cancel the pairing.
        var mine: PairingSocket? = null
        fun current() = mine != null && socket === mine
        mine = try {
            open(ctx, CloudRelay.PairingRoute(parentKey, room.token, authorization,
                onReady = { main.post { if (current() && !ended) hello?.let { o -> socket?.publish(o.id, o.wire) {} } } },
                onRecord = { record -> main.post { if (current()) received(record) } },
                onRefused = { main.post { if (current() && !ended) { refused = true; machine.cancel(); stop() } } }))
        } catch (_: Exception) { refused = true; machine.cancel(); stop(); null }
        socket = mine
    }
    private fun received(record: JSONObject) {
        if (ended) return
        when (machine.state) {
            ChildPairingState.AwaitingReply -> if (machine.onReply(record, clock())) {
                // M2 accepted: leave the rendezvous room now, before the parent retires it.
                socket?.close(); socket = null; hello = null
            }
            is ChildPairingState.ShowingDigits -> machine.onReply(record, clock())
            // Only M3 is the pairing's: anything else here (a parent command already queued in the new room) is neither acted on nor
            // allowed to cancel. A pair_complete that fails the machine's checks still cancels.
            ChildPairingState.AwaitingComplete -> if (Pairing.signedType(record) == "pair_complete")
                machine.onComplete(record, clock())?.let { result -> stop(); onResult(result); return } else return
            else -> {}
        }
        if (ended) stop() else onChange()
    }

    /** The child's own consent press (critical button): moves to the new family room with its v2 key proof and waits for M3. */
    fun confirm(): Boolean {
        val family = machine.confirm(clock()) ?: run { if (ended) stop() else onChange(); return false }
        val k = key ?: run { machine.cancel(); stop(); return false }
        hello = null
        // 409 until the parent has registered the room with K: the relay client retries every 15 s.
        connect(family) { FamilyRelay.childAuthorizationV2(parentKey, family.scope, family.token, k) }
        onChange()
        return true
    }

    fun tick() {
        if (ended) return
        machine.expire(clock())
        if (ended) stop() else onChange()
    }
    fun cancel() { if (!ended) { machine.cancel(); stop() } }
    private fun stop() {
        socket?.close(); socket = null; hello = null
        main.removeCallbacks(ticker)
        onChange()
    }
}
