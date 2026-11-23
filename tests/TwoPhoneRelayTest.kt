package app.family.igguard

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap
import java.util.UUID
import app.family.igguard.ChildPairingState as CS
import app.family.igguard.ParentPairingState as PS

/**
 * A fake relay with the observable behaviour of relay/src/worker.js for pairing (line numbers cite that file): per room a parent
 * registration, an optional child-key lock, one socket per role, one mailbox per role held until consumed, retirement.
 * Proofs are parsed, not verified (signatures are the real relay's and its own tests' concern).
 */
internal class FakeRelay : Dispatcher() {
    inner class Room {
        var registered = false
        var childKey: String? = null
        var retired = false
        val sockets = HashMap<String, WebSocket>()
        val socketKeys = HashMap<WebSocket, String?>()
        val inFlight = HashMap<WebSocket, MutableSet<String>>()
        /** Ordered by id like `ORDER BY id` in pump (worker.js:336): prefix, rank, hash. */
        val inbox = mapOf("parent" to TreeMap<String, String>(), "child" to TreeMap<String, String>())
        /** (role, packet id) of every publish this room accepted. */
        val published = mutableListOf<Pair<String, String>>()
    }
    val server = MockWebServer().also { it.dispatcher = this }
    val rooms = HashMap<String, Room>()
    /** "role:status" of every refused connection, in order. */
    val refusals = mutableListOf<String>()
    val url: String get() = server.url("/").toString()
    @Synchronized fun room(name: String) = rooms.getOrPut(name) { Room() }

    private fun hex(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun peer(role: String) = if (role == "parent") "child" else "parent"
    // worker.js:18-20: JSON error body and Retry-After in seconds.
    private fun refuse(role: String, status: Int, code: String, delay: Long = 60_000L): MockResponse {
        refusals += "$role:$status"
        return MockResponse().setResponseCode(status).setHeader("Retry-After", (delay / 1000).toString())
            .setBody(JSONObject().put("error", JSONObject().put("code", code).put("retry_after_ms", delay)).toString())
    }

    @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
        val name = request.path!!.substringAfterLast('/')
        val auth = try { JSONObject(String(Base64.getUrlDecoder().decode(request.getHeader("Authorization")!!.removePrefix("IGGuard ")))) }
            catch (_: Exception) { return refuse("?", 401, "unauthorized") }
        val role = auth.optString("role"); val v = auth.optInt("v")
        // worker.js:125-126: the proof names this room; only a parent may delete.
        if (auth.optString("room") != name || request.method == "DELETE" && role != "parent") return refuse(role, 401, "unauthorized")
        val room = room(name)
        // worker.js:133-136: a retired room answers 410 (a DELETE is acknowledged again).
        if (room.retired) return if (request.method == "DELETE") MockResponse().setResponseCode(204) else refuse(role, 410, "room_retired", 90L * 86_400_000L)
        if (role == "parent") {
            room.registered = true
            // worker.js:150-161: a v1 parent keeps the lock; a v2 parent sets it and closes children with another key.
            if (v == 2) {
                room.childKey = auth.getString("child_key")
                room.sockets["child"]?.let { if (room.socketKeys[it] != room.childKey) it.close(1000, "Child key replaced") }
            }
        } else {
            if (!room.registered) return refuse(role, 409, "parent_not_registered", 15_000)                                  // worker.js:166
            if (room.childKey != null && (v != 2 || auth.optString("child_key") != room.childKey)) return refuse(role, 401, "unauthorized") // :168
            if (v == 2 && room.childKey == null) return refuse(role, 409, "child_key_not_registered", 15_000)                // :176
        }
        if (request.method == "DELETE") { retire(room); return MockResponse().setResponseCode(204) }                      // :191-195
        return MockResponse().withWebSocketUpgrade(Listener(room, role, if (role == "child" && v == 2) auth.getString("child_key") else null))
    }

    /** worker.js:89-98: inboxes emptied, marked retired, every socket closed ("retired" is sent only to a socket that asked). */
    @Synchronized fun retire(room: Room) {
        room.retired = true; room.inbox.values.forEach { it.clear() }
        for (s in room.sockets.values.toList()) s.close(1000, "Room retired")
        room.sockets.clear()
    }

    /** worker.js:332-343: up to 8 unconsumed records in flight per socket, in id order. */
    private fun pump(room: Room, role: String) {
        val ws = room.sockets[role] ?: return
        val flight = room.inFlight.getOrPut(ws) { mutableSetOf() }
        for ((id, wire) in room.inbox.getValue(role)) {
            if (flight.size >= 8) return
            if (!flight.add(id)) continue
            ws.send(JSONObject().put("type", "message").put("id", id).put("wire", wire).put("digest", hex(wire))
                .put("delivery_id", UUID.randomUUID().toString()).put("buffered", true).toString())
        }
    }

    inner class Listener(private val room: Room, private val role: String, private val childKey: String?) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(this@FakeRelay) {
            if (room.retired) { webSocket.close(1000, "Room retired"); return }
            // worker.js:197-198: one socket per role; a new one replaces the old.
            room.sockets.put(role, webSocket)?.close(1000, "Replaced by authenticated reconnect")
            room.socketKeys[webSocket] = childKey
            val other = room.sockets[peer(role)]
            webSocket.send(JSONObject().put("type", "ready").put("protocol", 1).put("role", role).put("peer_online", other != null)
                .put("features", org.json.JSONArray().put("child_proof")).toString())                                        // :204
            other?.send(JSONObject().put("type", "peer").put("online", true).toString())                                      // :205-206
            pump(room, role)                                                                                                  // :207
        }
        override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(this@FakeRelay) {
            if (room.sockets[role] !== webSocket) return
            val p = JSONObject(text)
            when (p.optString("type")) {
                "publish" -> {
                    // worker.js:257-304: key = target prefix + rank + sha256(id); control is always buffered, other kinds go
                    // straight to an online peer.
                    val target = peer(role)
                    val rank = mapOf("control" to "0", "receipt" to "1", "urgent" to "2", "data" to "3").getValue(p.getString("kind"))
                    val key = (if (target == "parent") "p" else "c") + rank + hex(p.getString("id"))
                    room.published += role to p.getString("id")
                    val online = room.sockets[target]
                    if (p.getString("kind") != "control" && online != null) online.send(JSONObject().put("type", "message").put("id", key)
                        .put("wire", p.getString("wire")).put("digest", hex(p.getString("wire"))).put("delivery_id", UUID.randomUUID().toString()).put("buffered", false).toString())
                    else { room.inbox.getValue(target)[key] = p.getString("wire"); pump(room, target) }
                    webSocket.send(JSONObject().put("type", "accepted").put("id", p.getString("id")).put("mode", if (p.getString("kind") != "control" && online != null) "forwarded" else "buffered").toString())
                }
                "consume" -> {                                                                                                // :318-330
                    room.inbox.getValue(role).remove(p.getString("id")); room.inFlight[webSocket]?.remove(p.getString("id"))
                    webSocket.send(JSONObject().put("type", "consumed").put("id", p.getString("id")).toString()); pump(room, role)
                }
                "replay" -> { pump(room, role); webSocket.send("{\"type\":\"replay_complete\"}") }                             // :235-238
                else -> {}
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(webSocket)
        private fun gone(webSocket: WebSocket) = synchronized(this@FakeRelay) { if (room.sockets[role] === webSocket) room.sockets.remove(role); Unit }
    }
}

/** Both phones' real pairing code (ParentPairingLink + the parent's family connection, ChildPairingLink) through the real CloudRelay client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TwoPhoneRelayTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private lateinit var relay: FakeRelay
    private val links = mutableListOf<() -> Unit>()

    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        relay = FakeRelay(); relay.server.start()
        CloudRelay.endpoint = relay.url; CloudRelay.PAIRING_RETRY_MS = 300L
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
    }
    @After fun stop() {
        links.forEach { it() }; CloudRelay.disconnect(); idle()
        CloudRelay.endpoint = BuildConfig.RELAY_BASE_URL; CloudRelay.PAIRING_RETRY_MS = 15_000L
        relay.server.shutdown()
    }

    private fun waitUntil(what: String, seconds: Int = 20, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) { idle(); if (condition()) return; Thread.sleep(50) }
        idle(); assertTrue("timed out: $what", condition())
    }
    /** Keeps the main looper running for [ms] (relay callbacks are posted to it). */
    private fun settle(ms: Long) { val until = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < until) { idle(); Thread.sleep(50) } }
    private fun roomOf(token: String) = relay.room(FamilyRelay.room(GuardEngine.relayPublicKey(ctx), GuardEngine.getChannelTopicForToken(token)))

    private inner class Phones(clock: () -> Long = System::currentTimeMillis) {
        var result: ChildPairing.Result? = null
        val parent = ParentPairingLink(ctx, {}, clock = clock).also { p -> links += { p.cancel() } }
        val child = ChildPairingLink(ctx, {}, { result = it }).also { c -> links += { c.cancel() } }
        lateinit var qr: String
        fun showQr() { parent.begin(); waitUntil("QR shown") { parent.qrText != null }; qr = parent.qrText!! }
        fun upToDigits() {
            showQr()
            assertTrue(child.join(qr))
            waitUntil("both phones at the digits") { parent.machine.state is PS.EnterDigits && child.machine.state is CS.ShowingDigits }
        }
        val digits get() = (child.machine.state as CS.ShowingDigits).digits
        fun rendezvousRoom() = relay.rooms.values.first { r -> r.published.any { it.first == "child" } && r.childKey == null && r.registered }
    }

    private fun assertPaired(p: Phones) {
        waitUntil("the child phone has M3") { p.result != null }
        val r = p.result!!
        assertEquals(GuardEngine.getOrCreateFamilyToken(ctx), r.token)
        assertEquals(GuardEngine.pinnedChildKey(ctx), FamilySecurity.canonicalKeyText(r.childKey))
        assertEquals(GuardEngine.relayPublicKey(ctx), r.parentKey)
        assertTrue(FamilySecurity.verifiesPassword(QrPairing.PASSWORD, r.passwordVerifier))
        // The child's storage step (GuardEngine.completeChildPairing on its own phone) needs K to load and match.
        assertEquals(FamilySecurity.canonicalKeyText(r.childKey), FamilySecurity.canonicalKeyText(FamilySecurity.loadChildSigningKey(ctx, r.childKeyAlias).publicKey))
        assertEquals(CS.Paired, p.child.machine.state)
    }

    @Test fun parentAcceptsBeforeTheChildConfirms() {
        val p = Phones(); p.upToDigits()
        assertTrue(p.parent.enterDigits(p.digits))
        val rendezvous = p.rendezvousRoom()
        assertTrue(p.parent.accept())
        // M3 reaches the relay, then the parent retires the rendezvous room; the child's connection there must not end its pairing.
        waitUntil("rendezvous retired") { rendezvous.retired }
        settle(4_000) // longer than the client's first reconnect back-off (2-3 s)
        assertTrue("the child still shows the digits: ${p.child.machine.state}", p.child.machine.state is CS.ShowingDigits)
        assertTrue(p.child.confirm())
        assertPaired(p)
    }

    @Test fun theChildConfirmsBeforeTheParentAccepts() {
        val p = Phones(); p.upToDigits()
        val digits = p.digits
        assertTrue(p.child.confirm())
        settle(1_000)
        assertTrue("the child waits for the parent (409)", relay.refusals.contains("child:409"))
        assertTrue(p.parent.enterDigits(digits)); assertTrue(p.parent.accept())
        assertPaired(p)
    }

    @Test fun aSecondPhoneAfterTheFirstLeftTheRendezvousStillCancels() {
        val p = Phones(); p.upToDigits()
        val second = ChildPairingLink(ctx, {}, {}).also { c -> links += { c.cancel() } }
        assertTrue(second.join(p.qr))
        waitUntil("the parent stops") { p.parent.machine.state is PS.Cancelled }
        assertEquals(PS.Cancelled(Pairing.CancelReason.SECOND_PHONE), p.parent.machine.state)
        assertFalse(p.parent.accept()); assertNull(GuardEngine.pinnedChildKey(ctx))
    }

    @Test fun threeWrongDigitsCancelAndPinNothing() {
        val p = Phones(); p.upToDigits()
        val token = GuardEngine.getOrCreateFamilyToken(ctx)
        listOf("000000", "000001", "000002", "000003").filter { it != p.digits }.take(3).forEach { assertFalse(p.parent.enterDigits(it)) }
        assertEquals(PS.Cancelled(Pairing.CancelReason.WRONG_DIGITS), p.parent.machine.state)
        waitUntil("rendezvous retired") { p.rendezvousRoom().retired }
        assertNull(GuardEngine.pinnedChildKey(ctx)); assertEquals(token, GuardEngine.getOrCreateFamilyToken(ctx))
        assertNull(p.result)
    }
}
