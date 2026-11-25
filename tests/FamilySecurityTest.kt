package app.family.igguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FamilySecurityTest {
    private val now=1_800_000_000_000L
    private fun command(type:String="sync_trusted")=JSONObject().put("type",type).put("scope","family-channel-a")
        .put("command_id",UUID.randomUUID().toString()).put("ts_ms",now).put("trusted_csv","friend_one")
    @Test fun saltedVerifierAcceptsPasswordButIsNotItselfAPassword() {
        val a=FamilySecurity.passwordVerifier("ParentPassword928!")
        val b=FamilySecurity.passwordVerifier("ParentPassword928!")
        assertNotEquals(a,b)
        assertTrue(FamilySecurity.verifiesPassword("ParentPassword928!",a))
        assertFalse(FamilySecurity.verifiesPassword("Incorrect928!",a))
        assertFalse(FamilySecurity.verifiesPassword(a,a))
        assertFalse(FamilySecurity.verifiesPassword("ParentPassword928!","pbkdf2-sha256:999999999:x:y"))
    }
    @Test fun onlyPinnedParentCanAuthorizeCommandForItsFamily() {
        val parent=FamilySecurity.newSigningIdentity()
        val attacker=FamilySecurity.newSigningIdentity()
        val signed=FamilySecurity.signCommand(command(),parent.privateKey)
        assertEquals("friend_one",FamilySecurity.readCommand(signed,parent.publicKey,"family-channel-a",now)!!.getString("trusted_csv"))
        assertNull(FamilySecurity.readCommand(signed,attacker.publicKey,"family-channel-a",now))
        assertNull(FamilySecurity.readCommand(signed,parent.publicKey,"family-channel-b",now))
        signed.put("payload",signed.getString("payload").replace("friend_one","attacker"))
        assertNull(FamilySecurity.readCommand(signed,parent.publicKey,"family-channel-a",now))
    }
    @Test fun safetyCodeBindsFamilyCredentialAndParentKey() {
        assertEquals("88AA-EC2F-4B27-90AE",FamilySecurity.safetyCode("token","verifier","parent-key"))
        assertNotEquals(FamilySecurity.safetyCode("token","verifier","parent-key"),FamilySecurity.safetyCode("token","verifier","other-key"))
        assertNotEquals(FamilySecurity.safetyCode("token","verifier","parent-key"),FamilySecurity.safetyCode("token","changed-verifier","parent-key"))
    }
    @Test fun invalidAndExpiredCommandsAreRejectedEvenWithValidSignatures() {
        val parent=FamilySecurity.newSigningIdentity()
        val cases=listOf(
            command("remote_reset_child").put("ts_ms",0),
            command("remote_reset_child").put("ts_ms",now+3*60_000),
            command("remote_reset_child").put("ts_ms",now-10*86_400_000L-1),
            command().put("command_id",""),
            command("unlock_code").put("code","234ABC").put("exp",now),
            command("unlock_code").put("code","234ABC").put("exp",now+16*60_000),
            command("key_rotated").put("new_token","not a valid token").put("new_pin","plainPassword928!")
        )
        for (c in cases) assertNull(c.toString(),FamilySecurity.readCommand(FamilySecurity.signCommand(c,parent.privateKey),parent.publicKey,"family-channel-a",now))
        val unlock=command("unlock_code").put("code","234ABC").put("exp",now+15*60_000)
        assertNotNull(FamilySecurity.readCommand(FamilySecurity.signCommand(unlock,parent.privateKey),parent.publicKey,"family-channel-a",now))
    }
    private val day=86_400_000L
    /** A key rotation lives as long as the parent's outbox (3 days) and then the relay (7 days) can hand it to an offline child. */
    @Test fun aKeyRotationLivesAsLongAsTheOutboxAndTheRelayHoldIt() {
        val parent=FamilySecurity.newSigningIdentity()
        fun read(c:JSONObject)=FamilySecurity.readCommand(FamilySecurity.signCommand(c,parent.privateKey),parent.publicKey,"family-channel-a",now)
        fun rotation(age:Long)=command("key_rotated").put("new_token",UUID.randomUUID().toString())
            .put("new_pin",FamilySecurity.passwordVerifier("ParentPassword928!")).put("ts_ms",now-age)
        assertNotNull("10 days old",read(rotation(10*day)))
        assertNull("older",read(rotation(10*day+1)))
        assertNotNull("a release too",read(command("remote_reset_child").put("ts_ms",now-10*day)))
        assertNotNull("ordinary commands keep 24 hours",read(command().put("ts_ms",now-day)))
        assertNull(read(command().put("ts_ms",now-day-1)))
    }
    /** RELAYED_COMMAND_LIFETIME_MS is the parent outbox's PENDING_TTL plus the relay's packet TTL, read from their sources: if either
     *  changes (or its form, so it can no longer be read here) without the constant following, this fails. */
    @Test fun theRelayedLifetimeIsTheOutboxTtlPlusTheRelayTtl() {
        val root=java.io.File(requireNotNull(System.getProperty("igguard.protocol.vector")){"igguard.protocol.vector is not set"}).parentFile.resolve("../..").canonicalFile
        val relayDays=requireNotNull(Regex("""const TTL = (\d+) \* 24 \* 60 \* 60 \* 1000;""").find(root.resolve("relay/src/worker.js").readText())){"relay TTL not found"}.groupValues[1].toLong()
        val outboxDays=requireNotNull(Regex("""PENDING_TTL=(\d+)\*DAY\b""").find(root.resolve("FamilyEvidence.kt").readText())){"PENDING_TTL not found"}.groupValues[1].toLong()
        assertEquals("outbox $outboxDays d + relay $relayDays d",(outboxDays+relayDays)*day,FamilySecurity.RELAYED_COMMAND_LIFETIME_MS)
    }
}
