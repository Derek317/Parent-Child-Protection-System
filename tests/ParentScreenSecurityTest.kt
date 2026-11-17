package app.family.igguard

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ParentScreenSecurityTest {
    private val ctx: Application get()=RuntimeEnvironment.getApplication()
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=false
        LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setRole(ctx,"parent")
    }
    @Test fun parentUiRequiresAuthenticationAndPreventsScreenCapture() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            assertTrue(GuardEngine.transferAllowed(ctx))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun parentNavigationStateSurvivesRotationWithoutPlaintextInSavedBundle() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val state=android.os.Bundle()
        try {
            val access=MainActivity::class.java.getDeclaredField("parentAccess").apply{isAccessible=true}.get(a) as ParentAccessSession
            access.authenticated(GuardEngine.captureRelaySession(ctx))
            MainActivity::class.java.getDeclaredField("evidenceSearch").apply{isAccessible=true}.set(a,"private-search-marker")
            c.saveInstanceState(state)
            assertFalse(state.toString().contains("private-search-marker"))
        } finally {c.pause().stop().destroy()}
        val restored=Robolectric.buildActivity(MainActivity::class.java).setup(state)
        try {
            assertEquals("private-search-marker",MainActivity::class.java.getDeclaredField("evidenceSearch").apply{isAccessible=true}.get(restored.get()))
            assertTrue(all(restored.get().window.decorView).any{it.tag=="parent_privacy_lock"})
        } finally {restored.pause().stop().destroy()}
    }
    @Test fun cancelledOrStaleAuthenticationCannotUnlockCurrentFamily() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val field=MainActivity::class.java.getDeclaredField("parentAuthSession").apply{isAccessible=true}
            val complete=MainActivity::class.java.getDeclaredMethod("completeParentAuthentication",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
            field.set(a,GuardEngine.captureRelaySession(ctx));complete.invoke(a,false)
            assertTrue(all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            field.set(a,GuardEngine.captureRelaySession(ctx));GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString());complete.invoke(a,true)
            assertTrue(all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
        } finally {c.pause().stop().destroy()}
    }
    @Test fun staleParentButtonCannotRevealSecretsAfterBackground() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val access=MainActivity::class.java.getDeclaredField("parentAccess").apply{isAccessible=true}.get(a) as ParentAccessSession
            access.authenticated(GuardEngine.captureRelaySession(ctx))
            MainActivity::class.java.getDeclaredMethod("renderUi",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,false)
            c.pause()
            assertFalse(access.isUnlocked(GuardEngine.captureRelaySession(ctx)))
            assertTrue(all(a.window.decorView).any{it.tag=="parent_privacy_lock"})
            assertTrue(GuardEngine.transferAllowed(ctx))
        } finally {c.stop().destroy()}
    }
}
