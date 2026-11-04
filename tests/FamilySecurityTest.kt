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
            command("remote_reset_child").put("ts_ms",now-21*60_000),
            command().put("command_id",""),
            command("unlock_code").put("code","234ABC").put("exp",now),
            command("unlock_code").put("code","234ABC").put("exp",now+16*60_000),
            command("key_rotated").put("new_token","not a valid token").put("new_pin","plainPassword928!")
        )
        for (c in cases) assertNull(c.toString(),FamilySecurity.readCommand(FamilySecurity.signCommand(c,parent.privateKey),parent.publicKey,"family-channel-a",now))
        val unlock=command("unlock_code").put("code","234ABC").put("exp",now+15*60_000)
        assertNotNull(FamilySecurity.readCommand(FamilySecurity.signCommand(unlock,parent.privateKey),parent.publicKey,"family-channel-a",now))
    }
}
