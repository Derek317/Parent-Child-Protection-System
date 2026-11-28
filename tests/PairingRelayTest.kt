package app.family.igguard

import android.app.Application
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The relay client for QR pairings: the temporary pairing room, v1/v2 proofs and the child's 409/401 outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PairingRelayTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        GuardEngine.cpChildKeyRefused = false; FamilyRelay.clockOffsetMs = 0L
    }
    private fun waitUntil(condition: () -> Boolean) { var n = 0; while (!condition() && n++ < 60) Thread.sleep(100) }
    private fun header(request: okhttp3.mockwebserver.RecordedRequest) =
        JSONObject(String(java.util.Base64.getUrlDecoder().decode(request.getHeader("Authorization")!!.removePrefix("IGGuard "))))
    private fun cooldownLeft(): Long {
        val room = FamilyRelay.room(GuardEngine.relayPublicKey(ctx), GuardEngine.getChannelTopic(ctx))
        return ctx.getSharedPreferences("ig_guard_prefs", android.content.Context.MODE_PRIVATE).getLong("relay_cooldown_child_$room", 0) - System.currentTimeMillis()
    }
    private fun httpDate(ms: Long) = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC))
    private fun refuse(status: Int, code: String, serverDate: Long? = null): Pair<MockWebServer, okhttp3.mockwebserver.RecordedRequest> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "15").setBody("{\"error\":{\"code\":\"$code\",\"retry_after_ms\":15000}}")
            .apply { if (serverDate != null) setHeader("Date", httpDate(serverDate)) })
        server.start()
        val connection = CloudRelay.Connection(ctx, GuardEngine.captureRelaySession(ctx), GuardEngine.getOrCreateFamilyToken(ctx), server.url("/").toString())
        connection.start()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        waitUntil { cooldownLeft() > 0 }
        Thread.sleep(200)
        connection.close()
        return server to request
    }

    @Test fun aKeyedChildProvesItsKeyAndWaitsExactly15sFor409() {
        QrPairing.pairChild(ctx)
        val (server, request) = refuse(409, "parent_not_registered")
        try {
            assertEquals(2, header(request).getInt("v"))
            val left = cooldownLeft()
            assertTrue("409 means retry in 15 s, not the growing back-off: $left", left in 10_000L..15_000L)
            assertTrue(GuardEngine.cpLastPollStatus.contains("15 秒"))
            assertFalse(GuardEngine.cpChildKeyRefused)
        } finally { server.shutdown() }
    }

    @Test fun a401ToAKeyedChildSaysPairAgainAndWipesNothing() {
        val r = QrPairing.pairChild(ctx)
        val (server, _) = refuse(401, "unauthorized", System.currentTimeMillis())
        try {
            assertTrue(GuardEngine.cpChildKeyRefused)
            assertEquals(0L, FamilyRelay.clockOffsetMs)
            assertTrue(GuardEngine.cpLastPollStatus.contains("需要重新配對"))
            assertEquals(r.token, GuardEngine.getOrCreateFamilyToken(ctx)); assertEquals(r.childKeyAlias, GuardEngine.childKeyAlias(ctx))
            assertEquals("child", GuardEngine.getRole(ctx))
        } finally { server.shutdown() }
    }

    @Test fun aStaleTimeRefusalSaysCheckTheTimeNotPairAgain() {
        QrPairing.pairChild(ctx)
        val (server, _) = refuse(401, "stale_time", System.currentTimeMillis())
        try {
            assertFalse("not a key refusal", GuardEngine.cpChildKeyRefused)
            assertTrue(GuardEngine.cpLastPollStatus, GuardEngine.cpLastPollStatus.contains("日期和時間"))
            assertFalse(GuardEngine.cpLastPollStatus.contains("需要重新配對"))
        } finally { server.shutdown() }
    }

    @Test fun a401FromAPhoneClockOffBy10MinSaysCheckTheTimeAndSignsWithTheRelaysTime() {
        val r = QrPairing.pairChild(ctx)
        val (server, request) = refuse(401, "unauthorized", System.currentTimeMillis() + 10 * 60_000L)
        try {
            assertTrue("the refused proof used the phone's own clock", kotlin.math.abs(header(request).getLong("ts_ms") - System.currentTimeMillis()) < 60_000L)
            assertFalse("not a key refusal", GuardEngine.cpChildKeyRefused)
            assertTrue(GuardEngine.cpLastPollStatus, GuardEngine.cpLastPollStatus.contains("時間"))
            assertFalse(GuardEngine.cpLastPollStatus.contains("需要重新配對"))
            assertTrue("retried soon, not after 5 min: ${cooldownLeft()}", cooldownLeft() <= 15_000L)
            val proof = JSONObject(String(java.util.Base64.getUrlDecoder().decode(GuardEngine.relayAuthorization(ctx, r.token).removePrefix("IGGuard "))))
            assertTrue("the next proof carries the relay's time", kotlin.math.abs(proof.getLong("ts_ms") - (System.currentTimeMillis() + 10 * 60_000L)) < 60_000L)
            assertEquals(r.childKeyAlias, GuardEngine.childKeyAlias(ctx)); assertEquals("child", GuardEngine.getRole(ctx))
        } finally { server.shutdown(); FamilyRelay.clockOffsetMs = 0L }
    }

    @Test fun aSkewedRefusalAfterARestartStillCorrectsTheClock() {
        val now = System.currentTimeMillis(); val relay = now + 10 * 60_000L
        try {
            assertTrue(GuardEngine.noteRelayClock(ctx, relay, now))
            FamilyRelay.clockOffsetMs = 0L
            assertTrue("a proof signed with offset 0 was refused for the clock", GuardEngine.noteRelayClock(ctx, relay, now))
            assertEquals(relay - now, FamilyRelay.clockOffsetMs)
            FamilyRelay.clockOffsetMs = 0L
            GuardEngine.loadRelayClock(ctx)
            assertEquals("the pairing proof can load the saved offset first", relay - now, FamilyRelay.clockOffsetMs)
        } finally { FamilyRelay.clockOffsetMs = 0L }
    }

    @Test fun aReadyConnectionClearsAnEarlierKeyRefusal() {
        QrPairing.pairChild(ctx)
        GuardEngine.cpChildKeyRefused = true
        val server = MockWebServer()
        val relaySide = java.util.concurrent.atomic.AtomicReference<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { relaySide.set(webSocket); webSocket.send(JSONObject().put("type", "ready").put("protocol", 1).toString()) }
        }))
        server.start()
        val connection = CloudRelay.Connection(ctx, GuardEngine.captureRelaySession(ctx), GuardEngine.getOrCreateFamilyToken(ctx), server.url("/").toString())
        try {
            connection.start()
            waitUntil { !GuardEngine.cpChildKeyRefused }
            assertFalse(GuardEngine.cpChildKeyRefused)
        } finally { connection.close(); relaySide.get()?.close(1000, null); server.shutdown() }
    }

    @Test fun anOldPairingKeepsV1AndItsOrdinaryBackoff() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        val invite = QrPairing.legacyInvite(ctx)
        GuardEngine.setRole(ctx, "unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx, invite))
        val (server, request) = refuse(401, "unauthorized")
        try {
            assertEquals(1, header(request).getInt("v"))
            assertFalse("a 5.2 child is not told to pair again on 401", GuardEngine.cpChildKeyRefused)
        } finally { server.shutdown() }
    }

    @Test fun aClockRefusalOnAPairingRoomRetriesWithTheRelaysTimeInsteadOfEndingThePairing() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Date", httpDate(System.currentTimeMillis() + 10 * 60_000L))
            .setBody("{\"error\":{\"code\":\"stale_time\",\"retry_after_ms\":0}}"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(JSONObject().put("type", "ready").put("protocol", 1).toString()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })); server.start()
        val ready = CountDownLatch(1); var refused: Int? = null
        val token = PairingCrypto.newPairingSecret()
        val connection = CloudRelay.openPairingRoom(ctx, CloudRelay.PairingRoute(GuardEngine.relayPublicKey(ctx), token, { GuardEngine.relayAuthorization(ctx, token) },
            onReady = { ready.countDown() }, onRecord = {}, onRefused = { refused = it }), server.url("/").toString())
        try {
            assertTrue("the pairing room reconnects after the clock correction", ready.await(10, TimeUnit.SECONDS))
            assertNull("a clock refusal does not end the pairing", refused)
        } finally { connection.close(); server.shutdown(); FamilyRelay.clockOffsetMs = 0L }
    }
    @Test fun thePairingRoomCarriesM1AndM2AndRefusesAnOldRelay() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        for (childProof in listOf(false, true)) {
            val server = MockWebServer(); var serverSide: WebSocket? = null
            val opened = CountDownLatch(1); val published = CountDownLatch(1); val consumed = CountDownLatch(1)
            var m2wire = ""
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSide = webSocket
                    webSocket.send(JSONObject().put("type", "ready").put("protocol", 1).apply { if (childProof) put("features", org.json.JSONArray().put("child_proof")) }.toString())
                    opened.countDown()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val p = JSONObject(text)
                    when (p.optString("type")) {
                        "publish" -> { m2wire = p.getString("wire"); assertEquals("control", p.getString("kind")); webSocket.send(JSONObject().put("type", "accepted").put("id", p.getString("id")).put("mode", "buffered").toString()); published.countDown() }
                        "consume" -> consumed.countDown()
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            })); server.start()
            val opener: PairingOpener = { c, route ->
                val connection = CloudRelay.openPairingRoom(c, route, server.url("/").toString())
                object : PairingSocket {
                    override fun publish(id: String, wire: String, done: (Boolean) -> Unit) = connection.publish(id, wire, "control") { done(it.accepted) }
                    override fun close() = connection.close()
                }
            }
            val link = ParentPairingLink(ctx, {}, opener)
            try {
                link.begin()
                assertTrue(opened.await(5, TimeUnit.SECONDS))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("the rendezvous room uses a v1 parent proof", 1, header(request).getInt("v"))
                waitUntil { idle(); link.qrText != null || link.relay != ParentPairingLink.Relay.CONNECTING }
                if (!childProof) {
                    assertEquals(ParentPairingLink.Relay.TOO_OLD, link.relay); assertNull(link.qrText); continue
                }
                val qr = link.qrText!!
                val start = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)
                    .start(qr, "Pixel 7", System.currentTimeMillis())!!
                val wire = GuardEngine.encryptPayload(start.rendezvous.token, start.hello.toString())
                serverSide!!.send(JSONObject().put("type", "message").put("id", "p0" + "a".repeat(64)).put("digest", AlertFrames.digest(wire.toByteArray()))
                    .put("delivery_id", UUID.randomUUID().toString()).put("buffered", true).put("wire", wire).toString())
                assertTrue(consumed.await(5, TimeUnit.SECONDS))
                waitUntil { idle(); published.count == 0L }
                assertTrue(published.await(5, TimeUnit.SECONDS))
                val m2 = GuardEngine.decryptPayload(start.rendezvous.token, m2wire)!!
                assertEquals("pair_reply", JSONObject(m2.getJSONObject("envelope").getString("payload")).getString("type"))
                assertTrue(link.machine.state is ParentPairingState.EnterDigits)
            } finally { link.cancel(); server.shutdown() }
        }
    }
}
