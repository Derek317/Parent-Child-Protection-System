package app.family.igguard

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecurityRegressionTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val now get() = System.currentTimeMillis()
    @Before fun reset() {
        ctx.getSharedPreferences("ig_guard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.pauseTamperUntilMs = 0
    }
    private fun receive(inner: JSONObject, encrypted: Boolean = true) {
        val body = if (encrypted) GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx), inner.toString()) else inner.toString()
        val line = JSONObject().put("event", "message").put("id", java.util.UUID.randomUUID().toString()).put("message", body)
        GuardEngine.processIncomingRelayLine(ctx, line.toString(), GuardEngine.getRole(ctx), null)
    }
    @Test fun plaintextCannotResetChildProtection() {
        GuardEngine.setRole(ctx,"child")
        receive(JSONObject().put("type","remote_reset_child").put("ts_ms",now), encrypted=false)
        assertEquals("child",GuardEngine.getRole(ctx))
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
    }
    @Test fun encryptedUnsignedCommandsCannotChangeTrustedAccounts() {
        GuardEngine.setRole(ctx,"child")
        receive(JSONObject().put("type","sync_trusted").put("trusted_csv","injected_friend").put("ts_ms",now))
        assertTrue(GuardEngine.getTrustedHandles(ctx).isEmpty())
    }
    @Test fun malformedInvitesCannotSupplyFallbackOrShortPasswords() {
        val prefix="igguard://pair?token=01234567-89ab-cdef-0123-456789abcdef"
        for (suffix in listOf("", "&pw=A", "&pw=eA==")) assertNull(suffix,GuardEngine.parsePairingString(prefix+suffix))
    }
    @Test fun inviteDoesNotRevealParentMasterPassword() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"PrivateParent928!")
        val invite=GuardEngine.buildPairingInviteText(ctx)
        val link=invite.lineSequence().first{it.startsWith("igguard://")}
        val credential=String(Base64.decode(Uri.parse(link).getQueryParameter("pw"),Base64.NO_WRAP or Base64.URL_SAFE),Charsets.UTF_8)
        assertFalse(invite.contains("PrivateParent928!"))
        assertNotEquals("PrivateParent928!",credential)
    }
    @Test fun invitationTokenCannotDeriveTemporaryUnlockCodes() {
        GuardEngine.setRole(ctx,"child")
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val alphabet="23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val bucket=now/900_000L
        for (offset in -1L..1L) {
            val digest=MessageDigest.getInstance("SHA-256").digest("unlock15_v4.2:$token:${bucket+offset}".toByteArray(Charsets.UTF_8))
            val forged=(0 until 6).map{alphabet[(digest[it].toInt() and 255)%alphabet.length]}.joinToString("")
            assertFalse(GuardEngine.verify15MinUnlockCode(ctx,forged))
        }
    }
    @Test fun replacingFamilyClearsOldUnlockAndIdentityState() {
        GuardEngine.setRole(ctx,"child");GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.setTrustedHandles(ctx,listOf("old_friend"));GuardEngine.setChildSelfHandle(ctx,"old_child")
        GuardEngine.grantTemporaryUnlock(ctx,60_000)
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit()
            .putString("synced_15m_code","234ABC").putLong("synced_15m_exp",now+60_000)
            .putLong("last_control_ts_ms",now).commit()
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString())
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        assertFalse(GuardEngine.verify15MinUnlockCode(ctx,"234ABC"))
        assertTrue(GuardEngine.getTrustedHandles(ctx).isEmpty())
        assertEquals("",GuardEngine.getChildSelfHandle(ctx))
    }
    @Test fun bodyPrefixCannotImpersonateTrustedSender() {
        GuardEngine.setTrustedHandles(ctx,listOf("family_friend"))
        assertEquals("stranger_message",GuardEngine.evaluateMessage(ctx,"outsider","family_friend: hello"))
    }
    @Test fun displayNameCannotImpersonateTrustedHandle() {
        GuardEngine.setTrustedHandles(ctx,listOf("family_friend"))
        assertEquals("stranger_message",GuardEngine.evaluateMessage(ctx,"family_friend outsider","hello there"))
        assertNull(GuardEngine.evaluateMessage(ctx,"@family_friend","hello there"))
    }
    @Test fun cachedHeartbeatDoesNotMakeOfflineChildAppearOnline() {
        GuardEngine.setRole(ctx,"parent")
        receive(JSONObject().put("type","alert").put("reason","heartbeat_ok").put("ts_ms",now-101*60_000L).put("text","old heartbeat"))
        assertTrue(GuardEngine.isChildHeartbeatOverdue(ctx))
    }
    @Test fun pairingConfirmationStartsOfflineDeadlineBeforeFirstHeartbeat() {
        GuardEngine.setRole(ctx,"parent")
        receive(JSONObject().put("type","alert").put("reason","pairing_verified").put("ts_ms",now-101*60_000L).put("text","paired earlier"))
        assertTrue(GuardEngine.isChildHeartbeatOverdue(ctx))
    }
}
