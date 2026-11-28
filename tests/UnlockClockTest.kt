package app.family.igguard

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** F2(b)-(d): temporary unlock, lockout and unlock codes do not depend on a changeable wall clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UnlockClockTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    private var wall=System.currentTimeMillis()
    private var mono=5_000_000L
    private var boot="7"
    private val minute=60_000L
    @Before fun setUp() {
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        GuardEngine.pauseTamperUntilMs=0
        GuardEngine.wallClock={wall};GuardEngine.monotonicClock={mono};GuardEngine.bootMarker={boot}
        GuardEngine.setRole(ctx,"child");GuardEngine.getOrCreateFamilyToken(ctx)
    }
    @After fun restore() {
        GuardEngine.wallClock={System.currentTimeMillis()};GuardEngine.monotonicClock={android.os.SystemClock.elapsedRealtime()}
        GuardEngine.bootMarker={"-1"}
    }
    /** Pair this device as a child of a (simulated) parent and deliver a really signed unlock_code, as the relay would. */
    private fun provisionCode(code:String) {
        prefs.edit().clear().commit();GuardEngine.pauseTamperUntilMs=0
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val invite=QrPairing.legacyInvite(ctx)
        val key=FamilySecurity.loadOrCreateParentIdentity(ctx,null,invite.parentPublicKey).privateKey
        GuardEngine.setRole(ctx,"unconfigured")
        prefs.edit().remove("parent_master_password").remove("parent_share_verifier").remove("parent_signing_public").commit()
        assertTrue(GuardEngine.applyPairing(ctx,invite))
        val command=JSONObject().put("type","unlock_code").put("scope",GuardEngine.getChannelTopic(ctx)).put("code",code)
            .put("exp",wall+15*minute).put("command_id",UUID.randomUUID().toString()).put("ts_ms",System.currentTimeMillis())
        val wire=GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),FamilySecurity.signCommand(command,key).toString())
        val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",wire)
        assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null))
        assertTrue(GuardEngine.verify15MinUnlockCode(ctx,code))
    }
    private fun advance(ms:Long){wall+=ms;mono+=ms}
    private fun queuedAlerts():List<JSONObject> {
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        return FamilyEvidence(ctx).use{it.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE)}.mapNotNull{GuardEngine.decryptPayload(token,it.wire)}
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()

    @Test fun temporaryUnlockEndsAtReboot() {
        GuardEngine.grantTemporaryUnlock(ctx,15*minute)
        assertTrue(GuardEngine.isTemporarilyUnlocked(ctx))
        boot="8";mono=1_000L   // reboot: boot count changes and elapsedRealtime restarts
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        assertEquals(0L,GuardEngine.getUnlockRemainingSeconds(ctx))
        boot="7";mono=5_000_000L
        assertFalse("an expired unlock must not revive",GuardEngine.isTemporarilyUnlocked(ctx))
    }
    @Test fun rebootIsDetectedEvenWhenTheBootMarkerIsUnavailable() {
        boot="-1"
        GuardEngine.grantTemporaryUnlock(ctx,15*minute)
        mono=10_000L   // elapsedRealtime went backwards: only possible after a reboot
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
    }
    @Test fun movingTheWallClockBackCannotExtendAnUnlock() {
        GuardEngine.grantTemporaryUnlock(ctx,3*minute)
        mono+=4*minute;wall-=60*minute
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
    }
    @Test fun wallClockExpiryRemainsAnAdditionalUpperBound() {
        GuardEngine.grantTemporaryUnlock(ctx,15*minute)
        wall+=20*minute   // clock jumped forward while little monotonic time passed
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
    }
    @Test fun unlockRemainsForItsFullDurationWhenNothingIsTampered() {
        GuardEngine.grantTemporaryUnlock(ctx,3*minute)
        advance(2*minute);assertTrue(GuardEngine.isTemporarilyUnlocked(ctx))
        assertTrue(GuardEngine.getUnlockRemainingSeconds(ctx) in 55..60)
        advance(2*minute);assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
    }
    @Test fun lockoutSurvivesRebootAndClockChanges() {
        repeat(5){GuardEngine.recordPasswordAttempt(ctx,false)}
        assertTrue(GuardEngine.checkPasswordLockoutRemainingSec(ctx)in 590..600)
        wall+=3*60*minute   // clock moved forward: must not skip the lockout
        assertTrue(GuardEngine.checkPasswordLockoutRemainingSec(ctx)>=590)
        wall-=6*60*minute;mono+=5*minute
        assertTrue(GuardEngine.checkPasswordLockoutRemainingSec(ctx)in 290..300)
        boot="8";mono=2_000L
        assertTrue("a reboot resumes the lockout, never ends it",GuardEngine.checkPasswordLockoutRemainingSec(ctx)in 290..300)
        mono+=11*minute
        assertEquals(0L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
    }
    @Test fun lockoutResumesFromItsObservedRemainingTimeAfterReboot() {
        repeat(5){GuardEngine.recordPasswordAttempt(ctx,false)}
        mono+=100_000L
        assertEquals(500L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
        boot="8";mono=2_000L;wall+=3*60*minute
        assertEquals(500L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
        mono+=499_000L
        assertEquals(1L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
        mono+=1_000L
        assertEquals(0L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
    }
    @Test fun movingTheWallClockForwardNeverShortensALockoutBeforeOrAfterReboot() {
        repeat(5){GuardEngine.recordPasswordAttempt(ctx,false)}
        wall+=5*60*minute
        assertEquals(600L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
        boot="8";mono=2_000L;wall+=24*60*minute
        assertEquals(600L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
        wall+=24*60*minute
        assertEquals(600L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
    }
    @Test fun anEndedLockoutNobodyLookedAtStaysEndedAfterReboot() {
        repeat(5){GuardEngine.recordPasswordAttempt(ctx,false)}
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onListenerConnected()
            mono+=11*minute
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16))
        } finally { c.destroy() }
        boot="8";mono=2_000L
        mono+=3*60*minute
        assertEquals(0L,GuardEngine.checkPasswordLockoutRemainingSec(ctx))
    }
    @Test fun unlockCodeCannotBeReusedAfterRollingTheClockBack() {
        val code="234ABC";provisionCode(code)
        advance(16*minute)
        assertFalse(GuardEngine.verify15MinUnlockCode(ctx,code))
        wall-=60*minute
        assertFalse("rolling the wall clock back must not revive an expired code",GuardEngine.verify15MinUnlockCode(ctx,code))
    }
    @Test fun unlockCodeWorksOnlyOnceThroughTheUnlockScreen() {
        val code="234ABC";provisionCode(code)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"unlock")
            fun submit(text:String){
                all(a.window.decorView).filterIsInstance<EditText>().first().setText(text)
                all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="驗證並暫時解鎖"}.performClick()
                // A text that is not a valid code goes on to the password check, which runs off the main thread.
                val end=System.currentTimeMillis()+20_000
                do org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() while(GuardEngine.passwordCheckRunning.get() && System.currentTimeMillis()<end && run{Thread.sleep(5);true})
            }
            submit(code)
            assertTrue(GuardEngine.isTemporarilyUnlocked(ctx))
            GuardEngine.grantTemporaryUnlock(ctx,0)
            submit(code)
            assertFalse("a used code must not unlock again",GuardEngine.isTemporarilyUnlocked(ctx))
            assertTrue(all(a.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("不正確")})
        } finally {c.pause().stop().destroy()}
    }
    @Test fun childRaisesOneTamperAlertWhenItsWallClockMovesBackwards() {
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)            // establishes the baseline
        assertTrue(queuedAlerts().none{it.optString("reason")=="tamper_attempt"})
        advance(10*minute);wall-=2*60*minute                         // clock set back two hours
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        val alerts=queuedAlerts().filter{it.optString("reason")=="tamper_attempt"}
        assertEquals(1,alerts.size)
        assertTrue(alerts[0].optString("text").contains("時間"))
        advance(minute);wall-=2*60*minute                            // a second rollback inside the window stays quiet
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        assertEquals(1,queuedAlerts().count{it.optString("reason")=="tamper_attempt"})
    }
    @Test fun heartbeatsDoNotStopForHoursAfterTheWallClockMovesBack() {
        prefs.edit().putLong("next_heartbeat_at_ms",System.currentTimeMillis()+3*60*minute).commit()
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        assertTrue(queuedAlerts().any{it.optString("sender")=="子女手機定時回報"})
        assertTrue(prefs.getLong("next_heartbeat_at_ms",0L)<=System.currentTimeMillis()+95*minute)
    }
    @Test fun aRollbackBeyondTheCommandToleranceAlerts() {
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        advance(10*minute);wall-=3*minute
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        assertEquals(1,queuedAlerts().count{it.optString("reason")=="tamper_attempt"})
    }
    @Test fun smallClockCorrectionsAndForwardJumpsDoNotAlert() {
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        advance(10*minute);wall-=2*minute
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        wall+=5*60*minute
        GuardEngine.checkAndSendChildHeartbeatIfDue(ctx)
        assertTrue(queuedAlerts().none{it.optString("reason")=="tamper_attempt"})
    }
}
