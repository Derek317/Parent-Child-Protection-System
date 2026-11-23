package app.family.igguard

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.robolectric.Shadows.shadowOf

/** In-memory pairing rooms for ParentPairingLink / ChildPairingLink: what a link publishes and the route it opened with. */
internal class FakeRooms {
    inner class Room(val route: CloudRelay.PairingRoute) : PairingSocket {
        val published = mutableListOf<Pair<String, String>>()
        var closed = false
        override fun publish(id: String, wire: String, done: (Boolean) -> Unit) { published += id to wire; done(true) }
        override fun close() { closed = true }
        /** The plaintext of what was published here, decrypted under this room's token. */
        fun records(): List<JSONObject> = published.map { GuardEngine.decryptPayload(route.token, it.second)!! }
        fun ready(childProof: Boolean = true) { route.onReady(JSONObject().put("type", "ready").put("protocol", 1).apply { if (childProof) put("features", JSONArray().put("child_proof")) }); idle() }
        /** Delivers [record] as the relay would: encrypted under the room token, decrypted on arrival. */
        fun deliver(record: JSONObject) { route.onRecord(GuardEngine.decryptPayload(route.token, GuardEngine.encryptPayload(route.token, record.toString()))!!); idle() }
        fun authorization(): JSONObject = JSONObject(String(java.util.Base64.getUrlDecoder().decode(route.authorization().removePrefix("IGGuard "))))
    }
    val rooms = mutableListOf<Room>()
    val opener: PairingOpener = { _, route -> Room(route).also { rooms += it } }
}

internal fun idle() = shadowOf(Looper.getMainLooper()).idle()

internal object QrPairing {
    val palette = GuardEngine.EMOJI_PALETTE.toList()
    const val PASSWORD = "ParentPrivate928!"

    /** A 5.2 pairing as it is still stored after the upgrade: the child phone holds token, verifier and parent key (no child key). */
    fun legacyInvite(ctx: Context): FamilySecurity.Invite =
        FamilySecurity.Invite(GuardEngine.getOrCreateFamilyToken(ctx), GuardEngine.parentPasswordVerifier(ctx), GuardEngine.relayPublicKey(ctx))

    /** A software parent for driving a real child engine against the bare ParentPairing machine. */
    fun bareParent(identity: FamilySecurity.SigningIdentity) = ParentPairing({ FamilySecurity.signCommand(it, identity.privateKey) }, palette)

    /**
     * The child side of a QR pairing on THIS phone (real ChildPairingLink + GuardEngine.completeChildPairing) against a bare parent
     * machine with [identity]. Returns the stored result. One app context per test, so the parent is the bare machine here.
     */
    fun pairChild(ctx: Context, identity: FamilySecurity.SigningIdentity = FamilySecurity.newSigningIdentity(),
                  verifier: String = FamilySecurity.passwordVerifier(PASSWORD), password: String? = null, now: Long = System.currentTimeMillis()): ChildPairing.Result {
        val parent = bareParent(identity)
        val start = parent.start(identity.publicKey, now)
        val rooms = FakeRooms()
        var result: ChildPairing.Result? = null
        val link = ChildPairingLink(ctx, {}, { result = it }, rooms.opener, clock = { now + 1_000 })
        assertTrue(link.join(start.qrText))
        rooms.rooms[0].ready()
        val m2 = parent.onHello(rooms.rooms[0].records().single(), now + 2_000)!!
        rooms.rooms[0].deliver(m2)
        assertTrue(parent.digitsEntered((link.machine.state as ChildPairingState.ShowingDigits).digits))
        assertTrue(link.confirm())
        val done = parent.accept(verifier, now + 3_000)!!
        rooms.rooms[1].deliver(done.complete)
        val r = result!!
        assertTrue(GuardEngine.completeChildPairing(ctx, r, password))
        return r
    }
}
