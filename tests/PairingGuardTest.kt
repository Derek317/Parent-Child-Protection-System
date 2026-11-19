package app.family.igguard

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.UUID

/** F3: pairing links never act on a parent phone, and never delete parent secrets as a side effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PairingGuardTest {
    private val ctx: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun reset() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        ParentUiAuthFixture.autoApprove=true
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun foreignInvite()=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)
    private fun link(i:FamilySecurity.Invite)=Uri.parse("igguard://pair?version=43&token=${i.token}&pw=${FamilySecurity.encode(i.passwordVerifier)}&parent=${i.parentPublicKey}")

    @Test fun parentPhoneRefusesPairingAndKeepsEverySecret() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val publicKey=GuardEngine.relayPublicKey(ctx)
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        assertEquals("ParentPrivate928!",prefs.getString("parent_master_password",null))
        assertEquals(publicKey,GuardEngine.relayPublicKey(ctx))
    }
    @Test fun pairingLinkOnParentPhoneShowsPlainMessageAndNoConfirmation() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val intent=Intent(Intent.ACTION_VIEW,link(foreignInvite()))
        val c=Robolectric.buildActivity(MainActivity::class.java,intent).setup()
        try {
            assertTrue(ShadowToast.getTextOfLatestToast().contains("離開家庭"))
            val sheet=ShadowDialog.getLatestDialog()
            assertTrue(sheet==null || all(sheet.window!!.decorView).filterIsInstance<Button>().none{it.text.toString().contains("確認配對")})
            assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
            assertEquals("ParentPrivate928!",prefs.getString("parent_master_password",null))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun unconfiguredPhoneStillHoldingParentSecretsDoesNotSilentlyDeleteThem() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val publicKey=GuardEngine.relayPublicKey(ctx)
        GuardEngine.setRole(ctx,"unconfigured")
        assertFalse(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("ParentPrivate928!",prefs.getString("parent_master_password",null))
        assertEquals(publicKey,prefs.getString("parent_signing_public",null))
    }
    @Test fun cleanUnconfiguredPhoneCanStillPair() {
        assertTrue(GuardEngine.applyPairing(ctx,foreignInvite()))
        assertEquals("child",GuardEngine.getRole(ctx))
    }
}
