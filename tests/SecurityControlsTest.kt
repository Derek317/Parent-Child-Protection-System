package app.family.igguard

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SecurityControlsTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        LocalVault.preferences(ctx).edit().clear().commit()
        ctx.deleteDatabase("family_evidence.db")
        GuardEngine.setRole(ctx,"child")
    }
    @Test fun aCriticalButtonIgnoresClicksUntilItHasSettledOnScreen() {
        // Robolectric runs with animators off (scale 0); the hidden setter turns them on, as on a phone.
        val scale=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.java).apply{isAccessible=true}
        scale.invoke(null,1f)
        val c=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup();val a=c.get()
        try {
            var busy=false;var clicks=0
            val b=ObscuredAwareButton(a,{busy}){}.apply{setOnClickListener{clicks++}}
            a.setContentView(b)
            assertFalse("a tap while the page or sheet is still moving in is ignored",b.performClick())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            busy=true
            assertFalse("a tap during a page fade or theme reveal is ignored",b.performClick())
            busy=false
            assertTrue(b.performClick());assertEquals(1,clicks)
            scale.invoke(null,0f)
            val instant=ObscuredAwareButton(a){}.apply{setOnClickListener{clicks++}}
            a.setContentView(instant)
            assertTrue("with animations off nothing moves, so nothing waits",instant.performClick());assertEquals(2,clicks)
        } finally {scale.invoke(null,0f);c.pause().stop().destroy()}
    }
    @Test fun withdrawalIsPersistentInvalidatesCallbacksAndPreventsNewCapture() {
        val old=GuardEngine.captureRelaySession(ctx)
        assertTrue(GuardEngine.collectionAllowed(ctx))
        GuardEngine.withdrawCollection(ctx, false)
        assertFalse(GuardEngine.collectionAllowed(ctx))
        assertFalse(GuardEngine.transferAllowed(ctx))
        assertNotEquals(old,GuardEngine.captureRelaySession(ctx))
        assertTrue(GuardEngine.captureMessages(ctx,listOf(CapturedText("peer","hello","incoming")),expectedSession=old).isEmpty())
    }
    @Test fun withdrawalCanHoldPendingButResumeExplicitlyDiscardsOldScope() {
        val scope=GuardEngine.getChannelTopic(ctx)
        FamilyEvidence(ctx).use{it.enqueue(scope,"old-message:0","old-ciphertext")}
        GuardEngine.withdrawCollection(ctx,false)
        assertEquals(1,FamilyEvidence(ctx).use{it.pendingCount(scope)})
        GuardEngine.resumeCollection(ctx)
        assertTrue(GuardEngine.collectionAllowed(ctx))
        assertEquals(0,FamilyEvidence(ctx).use{it.pendingCount(scope)})
        assertFalse(GuardEngine.fullCaptureEnabled(ctx))
    }
    @Test fun parentReceptionDoesNotDependOnChildWithdrawalOrUiUnlock() {
        GuardEngine.withdrawCollection(ctx,true)
        GuardEngine.setRole(ctx,"parent")
        assertTrue(GuardEngine.transferAllowed(ctx))
        val access=ParentAccessSession()
        assertFalse(access.isUnlocked(GuardEngine.captureRelaySession(ctx)))
        assertTrue(GuardEngine.transferAllowed(ctx))
    }
    @Test fun parentScreenAuthenticationCannotSurviveBackgroundOrChangedFamily() {
        val access=ParentAccessSession();val a=Triple("parent","family-a",1)
        assertFalse(access.isUnlocked(a));access.authenticated(a);assertTrue(access.isUnlocked(a))
        assertFalse(access.isUnlocked(Triple("parent","family-b",1)))
        access.lock();assertFalse(access.isUnlocked(a))
    }
    @Test fun optionsExcludeOutgoingAndOrdinaryTrustedContextButKeepIncomingRisk() {
        GuardEngine.setCaptureOptions(ctx,false,false)
        assertFalse(GuardEngine.captureObservationAllowed(ctx,CapturedText("peer","hello","outgoing"),true,false))
        assertFalse(GuardEngine.captureObservationAllowed(ctx,CapturedText("peer","hello","incoming"),true,false))
        assertTrue(GuardEngine.captureObservationAllowed(ctx,CapturedText("peer","send nudes","incoming"),true,false))
        assertTrue(GuardEngine.captureObservationAllowed(ctx,CapturedText("peer","hello","incoming"),false,false))
    }
    @Test fun clipboardExpiryDoesNotEraseSomeoneElsesLaterCopy() {
        val clip=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        SensitiveClipboard.copy(ctx,"pairing","private invitation")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertFalse(clip.hasPrimaryClip())
        SensitiveClipboard.copy(ctx,"pairing","private invitation")
        clip.setPrimaryClip(android.content.ClipData.newPlainText("other","unrelated text"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertEquals("unrelated text",clip.primaryClip!!.getItemAt(0).text.toString())
    }
}
