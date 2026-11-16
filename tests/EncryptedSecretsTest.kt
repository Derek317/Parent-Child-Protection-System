package app.family.igguard

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.text.InputType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EncryptedSecretsTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val raw get() = ctx.getSharedPreferences("ig_guard_prefs", Context.MODE_PRIVATE)
    @Before fun reset() { raw.edit().clear().commit(); GuardEngine.pauseTamperUntilMs = 0 }

    @Test fun parentPasswordRemainsUsableWithoutPlaintextPreferences() {
        GuardEngine.setRole(ctx, "parent")
        GuardEngine.setPin(ctx, "SyntheticOnly928!")
        assertTrue(GuardEngine.verifyParentPassword(ctx, "SyntheticOnly928!"))
        assertNotEquals("SyntheticOnly928!", raw.getString("parent_master_password", null))
    }
    @Test fun parentSigningIdentityIsProtectedWithoutChangingPublicIdentity() {
        GuardEngine.setRole(ctx, "parent")
        val publicKey = GuardEngine.relayPublicKey(ctx)
        val stored = raw.getString("parent_signing_private", "").orEmpty()
        assertTrue("Stored signing key must be authenticated ciphertext", stored.startsWith("igv1:"))
        assertEquals(publicKey, GuardEngine.relayPublicKey(ctx))
    }
    @Test fun legacyPasswordMigratesWithoutChangingItsValue() {
        raw.edit().putString("role", "parent").putString("parent_master_password", "SyntheticLegacy928!").commit()
        assertEquals("SyntheticLegacy928!", GuardEngine.getPin(ctx))
        assertNotEquals("SyntheticLegacy928!", raw.getString("parent_master_password", null))
    }
    private fun children(view: View): List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    @Test fun unreadableCredentialsShowRecoveryWithoutErasingData() {
        ctx.deleteDatabase("family_evidence.db")
        LocalVault.preferences(ctx).edit().putString("role","parent").putString("parent_master_password","SyntheticOnly928!").commit()
        raw.edit().putString("parent_master_password","corrupted encrypted value").commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val text=children(controller.get().window.decorView).filterIsInstance<TextView>().joinToString { it.text.toString() }
        assertTrue(text.contains("無法開啟加密儲存空間"))
        assertEquals("corrupted encrypted value",raw.getString("parent_master_password",null))
        controller.pause().stop().destroy()
    }
    @Test fun typedPasswordIsNotCopiedIntoSavedActivityDrafts() {
        ctx.deleteDatabase("family_evidence.db")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        MainActivity::class.java.getDeclaredField("section").apply{isAccessible=true}.set(activity,"parent_setup")
        MainActivity::class.java.getDeclaredMethod("renderUi",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,false)
        val password=children(activity.window.decorView).filterIsInstance<EditText>().first{
            it.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        password.setText("SyntheticUnsaved928!")
        val saved=Bundle();controller.saveInstanceState(saved)
        assertFalse(saved.getBundle("drafts").toString().contains("SyntheticUnsaved928!"))
        controller.pause().stop().destroy()
    }
}
