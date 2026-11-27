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
 * It does NOT verify signatures: it is a liveness test double for the pairing flow, not a security test of the relay.
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
    /** Test switch: publishes are recorded but never answered or stored (a relay that does not take M3). */
    @Volatile var holdPublishes = false
    /** Test switch: every DELETE gets this status instead of the room logic; [deletes] counts DELETE requests. */
    @Volatile var deleteAnswer: Int? = null
    val deletes = java.util.concurrent.atomic.AtomicInteger()
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
        if (request.method == "DELETE") { deletes.incrementAndGet(); deleteAnswer?.let { return MockResponse().setResponseCode(it) } }
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
                    if (holdPublishes) { room.published += role to p.getString("id"); return }
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
        GuardEngine.forgetLivePairingsForTest() // machines other tests left in this JVM (e.g. Paired child machines) are not this test's
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
    }
    @After fun stop() {
        links.forEach { it() }; CloudRelay.disconnect(); idle()
        CloudRelay.endpoint = BuildConfig.RELAY_BASE_URL; CloudRelay.PAIRING_RETRY_MS = 15_000L
        relay.server.shutdown()
    }

    private fun waitUntil(what: String, seconds: Int = 45, seen: () -> String = { "" }, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) { idle(); if (condition()) return; Thread.sleep(50) }
        idle(); assertTrue("timed out: $what${seen()}", condition())
    }
    /** For a wait that timed out: what became of the records queued for [token]'s family, and what the relay client last said. No content. */
    private fun whyNotSent(token: String): String {
        val now = System.currentTimeMillis()
        val rows = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT role,relay_kind,attempts,retry_at,accepted FROM outbox WHERE scope=?", arrayOf(GuardEngine.getChannelTopicForToken(token))).use { c ->
            buildList { while (c.moveToNext()) add("${c.getString(0)}/${c.getString(1)} attempts=${c.getInt(2)} retry_in_ms=${c.getLong(3) - now} accepted=${c.getInt(4)}") } } }
        val room = roomOf(token)
        return " (published=${synchronized(relay) { room.published.map { it.first } }}, outbox=$rows, retired=${room.retired}, refusals=${synchronized(relay) { relay.refusals.toList() }}," +
            " status=${GuardEngine.cpLastHttpStatus} | ${GuardEngine.cpParentStreamState})"
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
        waitUntil("the child waits for the parent (409)") { synchronized(relay) { relay.refusals.contains("child:409") } }
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

    /** Commands the parent sends after Accept wait in the new room's child mailbox beside M3 (for "Pair again to verify" too:
     *  a 5.2 parent's commands still queued for the OLD family are not moved, FamilyEvidence.rotate moves only child rows). */
    @Test fun parentCommandsBesideM3HaveNoEffectAndStayForTheChildsFamilyConnection() {
        val p = Phones(); p.upToDigits()
        fun relayKey(frameId: String) = "c0" + MessageDigest.getInstance("SHA-256").digest(frameId.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(p.parent.enterDigits(p.digits)); assertTrue(p.parent.accept())
        val token = GuardEngine.getOrCreateFamilyToken(ctx); val family = roomOf(token)
        waitUntil("M3 at the relay", seen = { whyNotSent(token) }) { synchronized(relay) { family.published.size == 1 } }
        repeat(3) { GuardEngine.requestFullCapture(ctx) } // three signed capture_policy commands into the new room
        waitUntil("the commands reached the new room", seen = { synchronized(relay) { " (published=${family.published.map { it.first }}, child inbox=${family.inbox.getValue("child").size})" } }) { synchronized(relay) { family.published.size == 4 && family.inbox.getValue("child").size == 4 } }
        val keys = synchronized(relay) { family.published.drop(1).map { relayKey(it.second) } }
        assertTrue(p.child.confirm())
        assertPaired(p)
        settle(500)
        synchronized(relay) {
            assertTrue("the pairing route consumed none of them", family.inbox.getValue("child").keys.containsAll(keys))
            assertTrue("the unpaired child sent nothing (no receipt, no ack)", family.published.none { it.first == "child" })
        }
        // What happens to them afterwards: the child's own family connection (a new socket) gets them again.
        p.child.cancel()
        val received = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val key = FamilySecurity.loadChildSigningKey(ctx, p.result!!.childKeyAlias)
        val auth = FamilyRelay.childAuthorizationV2(GuardEngine.relayPublicKey(ctx), GuardEngine.getChannelTopicForToken(token), token, key)
        val socket = OkHttpClient().newWebSocket(Request.Builder().url(FamilyRelay.socketUrl(relay.url, FamilyRelay.room(GuardEngine.relayPublicKey(ctx),
            GuardEngine.getChannelTopicForToken(token)), true)).header("Authorization", auth).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { JSONObject(text).optString("id").let { if (it.isNotEmpty()) received += it } }
        })
        try { waitUntil("redelivered to the child's family connection") { received.containsAll(keys) } } finally { socket.cancel() }
    }

    /** A delivery worker of the family before the pairing is still ending when the parent accepts: M3 must not wait for a later trigger. */
    @Test fun m3IsSentWhenAnOlderDeliveryWorkerEndsAfterThePairingWasAccepted() {
        val p = Phones(); p.upToDigits()
        val ending = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        GuardEngine.beforeDeliveryWorkerEndsForTest = { GuardEngine.beforeDeliveryWorkerEndsForTest = null; ending.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            GuardEngine.requestFullCapture(ctx) // an older record of the family before the pairing
            assertTrue("the older delivery worker is ending", ending.await(20, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(p.parent.enterDigits(p.digits)); assertTrue(p.parent.accept())
            val family = roomOf(GuardEngine.getOrCreateFamilyToken(ctx))
            settle(1000) // the pairing's own requests to deliver were turned away: the older worker still holds the slot
            assertTrue("nothing was sent while the older worker held the slot", synchronized(relay) { family.published.isEmpty() })
            release.countDown()
            waitUntil("M3 at the relay after the older worker ended", seen = { synchronized(relay) { " (published=${family.published.map { it.first }})" } }) { synchronized(relay) { family.published.size == 1 } }
        } finally { GuardEngine.beforeDeliveryWorkerEndsForTest = null; release.countDown() }
    }

    /** A worker of an earlier family that ends after the family changed starts nothing by itself: only a request that was turned away does. */
    @Test fun aWorkerOfAnEarlierFamilyStartsNothingByItselfWhenItEnds() {
        val ended = java.util.concurrent.atomic.AtomicInteger(0)
        val ending = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        GuardEngine.beforeDeliveryWorkerEndsForTest = { if (ended.incrementAndGet() == 1) { ending.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS) } }
        try {
            GuardEngine.requestFullCapture(ctx) // a record of the earlier family: its worker is held as it ends
            assertTrue("the earlier worker is ending", ending.await(20, java.util.concurrent.TimeUnit.SECONDS))
            // The session changes (as a pairing, rotation or reset does) and a record is queued, but nobody asks for delivery.
            // The counter is bumped directly: every public way to change the session also connects, and a connection asks.
            val token = GuardEngine.getOrCreateFamilyToken(ctx)
            GuardEngine::class.java.getDeclaredField("relayGeneration").apply { isAccessible = true; setInt(null, getInt(null) + 1) }
            FamilyEvidence(ctx).use { it.enqueue(GuardEngine.getChannelTopicForToken(token), "control:leftover:0", "{}", role = "parent", kind = "control") }
            release.countDown(); settle(3000)
            assertEquals("the ending worker started no other", 1, ended.get())
            assertTrue("the queued record was not sent", synchronized(relay) { roomOf(token).published.none { it.second == "control:leftover:0" } })
        } finally { GuardEngine.beforeDeliveryWorkerEndsForTest = null; release.countDown() }
    }

    /** The parent's "取消並重新配對" and the automatic 10-minute rollback both retire the new family room: a late child gets 410. */
    @Test fun aRolledBackPairingRetiresTheNewRoomSoALateChildStops() {
        for (force in listOf(true, false)) {
            val p = Phones(); p.upToDigits()
            assertTrue(p.parent.enterDigits(p.digits)); assertTrue(p.parent.accept())
            val token = GuardEngine.getOrCreateFamilyToken(ctx); val family = roomOf(token)
            waitUntil("M3 at the relay (force=$force)", seen = { whyNotSent(token) }) { synchronized(relay) { family.published.size == 1 } }
            assertTrue(GuardEngine.expireUnconfirmedChildPairing(ctx, if (force) System.currentTimeMillis() else System.currentTimeMillis() + Pairing.CONFIRM_WINDOW_MS, force))
            waitUntil("the new room is retired (force=$force)") { family.retired }
            val alias = TestKeyStore.privateKeys.keys.single { it.startsWith("ig_guard.child_signing") }
            assertTrue(p.child.confirm())
            waitUntil("the late child stops (force=$force)") { p.child.machine.state is CS.Cancelled }
            assertTrue(p.child.refused); assertNull(p.result)
            assertFalse("its key is deleted", TestKeyStore.privateKeys.containsKey(alias))
            assertNull(GuardEngine.pinnedChildKey(ctx)); assertEquals(force, GuardEngine.childPairingCancelled(ctx))
        }
    }

    @Test fun anExpiredQrStopsAChildThatStillWaitsForM2() {
        var t = System.currentTimeMillis()
        val p = Phones { t }; p.showQr()
        t += PairingCrypto.QR_LIFETIME_MS; p.parent.tick()
        assertEquals(PS.Cancelled(Pairing.CancelReason.EXPIRED), p.parent.machine.state)
        // A child that scanned just before (its own clock still inside the QR's 3 minutes) finds the retired room.
        assertTrue(p.child.join(p.qr))
        waitUntil("the child stops") { p.child.machine.state is CS.Cancelled }
        assertTrue("told that the parent stopped", p.child.parentStopped)
        assertTrue(TestKeyStore.privateKeys.keys.none { it.startsWith("ig_guard.child_signing") })
    }

    @Test fun aParentCancelBeforeM2StopsTheChildAndAfterM2LeavesItWaitingVisibly() {
        // Before M2: the child is still in the rendezvous room when the parent retires it.
        val first = Phones(); first.showQr()
        val before = ChildPairingLink(ctx, {}, {}, clock = { System.currentTimeMillis() }).also { c -> links += { c.cancel() } }
        first.parent.cancel() // retires the rendezvous room
        waitUntil("rendezvous retired") { relay.rooms.values.any { it.retired } }
        assertTrue(before.join(first.qr))
        waitUntil("the child stops") { before.machine.state is CS.Cancelled }
        assertTrue(before.parentStopped)
        // After M2: the child has left the rendezvous room; the parent's cancel cannot reach it (no unsigned cancel exists).
        val p = Phones(); p.upToDigits()
        assertTrue(p.child.confirm())
        p.parent.cancel()
        settle(3_000)
        assertEquals(CS.AwaitingComplete, p.child.machine.state); assertFalse(p.child.parentStopped)
        assertTrue(p.child.confirmedAt > 0L); assertNull(GuardEngine.pinnedChildKey(ctx)); assertNull(p.result)
    }

    /** The rendezvous room is retired at Accept itself, not only after M3 reached the relay. */
    @Test fun acceptRetiresTheRendezvousRoomEvenIfM3NeverReachesTheRelay() {
        val p = Phones(); p.upToDigits()
        val rendezvous = p.rendezvousRoom()
        relay.holdPublishes = true
        assertTrue(p.parent.enterDigits(p.digits)); assertTrue(p.parent.accept())
        waitUntil("rendezvous retired at Accept") { rendezvous.retired }
        waitUntil("its token is forgotten once retired") { rendezvousTokens().isEmpty() }
    }

    /** A process death mid-pairing: the remembered rendezvous token lets the next app start retire the room. */
    @Test fun aRendezvousRoomLeftByAProcessDeathIsRetiredAtTheNextStart() {
        val p = Phones(); p.showQr()
        val secret = PairingCrypto.parsePairingQr(p.qr, System.currentTimeMillis())!!.secret
        val room = roomOf(secret)
        assertEquals(setOf(secret), rendezvousTokens())
        // The process "dies": its pairing machine is gone, nothing was retired.
        GuardEngine.forgetLivePairingsForTest()
        assertFalse(room.retired)
        GuardEngine.retireLeftoverRendezvous(ctx)
        waitUntil("retired at the next start") { room.retired }
        waitUntil("token forgotten") { rendezvousTokens().isEmpty() }
    }
    private fun rendezvousTokens() = prefs.getStringSet("pairing_rendezvous_tokens", emptySet()).orEmpty().map { it.substringAfter(':') }.toSet()

    /** Terminal answers (2xx, 404, 410) forget a rendezvous token; transient ones keep it for the next start. */
    @Test fun terminalRetirementAnswersForgetTheTokenTransientOnesKeepIt() {
        GuardEngine.forgetLivePairingsForTest()
        for (status in listOf(204, 200, 404, 410, 401, 408, 429, 500, 503)) {
            val token = UUID.randomUUID().toString()
            prefs.edit().remove("pairing_rendezvous_tokens").commit()
            GuardEngine.rememberRendezvous(ctx, token)
            relay.deleteAnswer = status
            val before = relay.deletes.get()
            GuardEngine.retireLeftoverRendezvous(ctx)
            waitUntil("DELETE sent ($status)") { relay.deletes.get() > before }
            if (status in 200..299 || status == 404 || status == 410) waitUntil("forgotten after $status") { rendezvousTokens().isEmpty() }
            else { settle(500); assertEquals("kept after $status", setOf(token), rendezvousTokens()) }
        }
        // No answer at all (network error): kept.
        val token = UUID.randomUUID().toString()
        prefs.edit().remove("pairing_rendezvous_tokens").commit(); GuardEngine.rememberRendezvous(ctx, token)
        relay.server.shutdown(); GuardEngine.retireLeftoverRendezvous(ctx); settle(2_000)
        assertEquals("kept after a network error", setOf(token), rendezvousTokens())
        relay = FakeRelay(); relay.server.start() // for @After
    }

    /** Storage stays bounded while a pairing runs: an aged entry is dropped, no DELETE is sent, the running pairing's token stays. */
    @Test fun agedRendezvousEntriesAreDroppedEvenWhileAPairingRuns() {
        val now = System.currentTimeMillis()
        val live = GuardEngine.newParentPairing(ctx)                     // a pairing in progress in this process
        prefs.edit().putStringSet("pairing_rendezvous_tokens", setOf("${now - GuardEngine.RENDEZVOUS_MAX_AGE_MS - 1}:stale", "$now:running")).commit()
        val before = relay.deletes.get()
        GuardEngine.retireLeftoverRendezvous(ctx, now); settle(500)
        assertEquals(setOf("running"), rendezvousTokens()); assertEquals("no request while a pairing runs", before, relay.deletes.get())
        assertEquals(PS.Idle, live.state)
    }

    @Test fun rememberedRendezvousTokensAreBoundedByAgeAndCount() {
        val now = System.currentTimeMillis()
        GuardEngine.rememberRendezvous(ctx, "old", now - GuardEngine.RENDEZVOUS_MAX_AGE_MS - 1)
        GuardEngine.rememberRendezvous(ctx, "recent", now)
        assertEquals("older than the relay's idle lifetime: dropped", setOf("recent"), rendezvousTokens())
        prefs.edit().remove("pairing_rendezvous_tokens").commit()
        for (i in 1..(GuardEngine.RENDEZVOUS_MAX + 3)) GuardEngine.rememberRendezvous(ctx, "t$i", now + i)
        assertEquals("capped, the oldest go first", (4..(GuardEngine.RENDEZVOUS_MAX + 3)).map { "t$it" }.toSet(), rendezvousTokens())
        // An entry that ages past the limit while stored is dropped at the next start without a request.
        prefs.edit().putStringSet("pairing_rendezvous_tokens", setOf("${now - GuardEngine.RENDEZVOUS_MAX_AGE_MS - 1}:stale")).commit()
        val before = relay.deletes.get()
        GuardEngine.retireLeftoverRendezvous(ctx, now); settle(500)
        assertTrue(rendezvousTokens().isEmpty()); assertEquals(before, relay.deletes.get())
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
