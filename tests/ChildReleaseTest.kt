package app.family.igguard

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.UUID

/** S1: leaving the parent role or recovering from compromise must not strand the paired child. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ChildReleaseTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    private val secret="ParentPrivate928!"
    @Before fun reset() {
        ParentUiAuthFixture.autoApprove=true
        prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        shadowOf(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceSecure(true)
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,secret)
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun call(a:MainActivity,name:String,vararg args:Any?){MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun answer(a:MainActivity){
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}.invoke(a,9031,Activity.RESULT_OK,null)
    }
    private fun waitFor(what:String,cond:()->Boolean){repeat(150){idle();if(cond())return;Thread.sleep(100)};fail("timed out: $what")}
    private fun latest():android.app.Dialog=ShadowDialog.getLatestDialog()
    private fun button(text:String)=all(latest().window!!.decorView).filterIsInstance<Button>().firstOrNull{it.text.toString()==text}
    private fun texts()=all(latest().window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
    private fun confirmLeave(a:MainActivity) {
        call(a,"navigateTo","role");call(a,"selectEntryRole","child");call(a,"continueEntryRole");idle()
        (latest() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
        answer(a)
    }
    private fun pendingWires(topic:String)=FamilyEvidence(ctx).use{it.due(topic,Long.MAX_VALUE,"parent")}.map{it.wire}
    private fun deliverToChild(wires:List<String>) {
        for(wire in wires){
            val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",wire)
            GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null)
        }
    }
    private fun foreign()=FamilySecurity.Invite(UUID.randomUUID().toString(),FamilySecurity.passwordVerifier("OtherParent928!"),FamilySecurity.newSigningIdentity().publicKey)

    /** The relay is unreachable in tests, which would (correctly) withdraw the queued reset; pretend it accepted the real command. */
    private fun relayAcceptsTheReset(){GuardEngine.releaseOverride={c,_->GuardEngine.rotateKeyAndNotifyChild(c,true);GuardEngine.ReleaseOutcome.SENT}}
    @Test fun leavingTheParentRoleSendsAResetShowsThePasswordAndFreesTheChild() {
        relayAcceptsTheReset()
        val invite=QrPairing.legacyInvite(ctx)
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            confirmLeave(a)
            assertEquals("the role must not change before the release step is finished","parent",GuardEngine.getRole(ctx))
            assertTrue("the plain explanation names the password",texts().any{it.contains(secret)})
            assertTrue(texts().any{it.contains("仍受保護")})
            button("繼續")!!.performClick()
            waitFor("release result"){button("我已記下，繼續")!=null}
            val wires=pendingWires(oldTopic)
            assertTrue("a signed reset command is queued for the child",wires.isNotEmpty())
            button("我已記下，繼續")!!.performClick();idle()
            assertEquals("unconfigured",GuardEngine.getRole(ctx));assertFalse(prefs.contains("parent_master_password"))
            // The child side: bound to the old family and unable to leave without the (now deleted) password...
            assertTrue(GuardEngine.applyPairing(ctx,invite))
            assertFalse(GuardEngine.applyPairing(ctx,foreign()))
            // ...until the signed reset arrives.
            deliverToChild(wires)
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
            assertTrue(GuardEngine.applyPairing(ctx,foreign()))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun leavingWithoutSendingStillShowsThePasswordNeededOnTheChild() {
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            confirmLeave(a)
            all(latest().window!!.decorView).filterIsInstance<android.widget.CheckBox>().first{it.tag=="release_child"}.isChecked=false
            button("繼續")!!.performClick()
            waitFor("result"){button("我已記下，繼續")!=null}
            assertTrue(pendingWires(oldTopic).isEmpty())
            assertTrue(texts().any{it.contains("輸入")&&it.contains("主密碼")})
            button("我已記下，繼續")!!.performClick();idle()
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun compromiseRecoveryAlsoReleasesTheChildBeforeDeletingThePassword() {
        relayAcceptsTheReset()
        val invite=QrPairing.legacyInvite(ctx)
        val oldTopic=GuardEngine.getChannelTopic(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            call(a,"navigateTo","settings")
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
            button("秘密可能外洩：重新安全配對")!!.performClick()
            button("只建立新配對（不刪除中繼資料）")!!.performClick();idle()
            (latest() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            answer(a)
            assertTrue(texts().any{it.contains(secret)})
            assertEquals(invite.token,GuardEngine.getOrCreateFamilyToken(ctx))
            button("繼續")!!.performClick()
            waitFor("release result"){button("我已記下，繼續")!=null}
            val wires=pendingWires(oldTopic)
            assertTrue(wires.isNotEmpty())
            button("我已記下，繼續")!!.performClick();idle()
            assertNotEquals("the family really was replaced",invite.token,GuardEngine.getOrCreateFamilyToken(ctx))
            assertNotEquals("the old master password is gone (a new family gets a new one)",secret,prefs.getString("parent_master_password",null))
            // simulate the child that still holds the old family
            GuardEngine.setRole(ctx,"unconfigured")
            prefs.edit().remove("parent_signing_public").remove("parent_master_password").remove("parent_share_verifier").commit()
            assertTrue(GuardEngine.applyPairing(ctx,invite))
            assertFalse(GuardEngine.applyPairing(ctx,foreign()))
            deliverToChild(wires)
            assertEquals("unconfigured",GuardEngine.getRole(ctx))
            assertTrue(GuardEngine.applyPairing(ctx,foreign()))
        } finally {c.pause().stop().destroy()}
    }

    // ---- second review: what the parent is told once the reset has gone out ----
    private fun openLeaveSheetWith(outcome:GuardEngine.ReleaseOutcome,a:MainActivity){
        GuardEngine.releaseOverride={_,_->outcome}
        confirmLeave(a);button("繼續")!!.performClick()
        waitFor("release result"){button("我已記下，繼續")!=null}
    }
    @org.junit.After fun clearOverride(){GuardEngine.releaseOverride=null;TestKeyStore.failEcGeneration=false}
    @Test fun onceTheResetIsAcceptedCancelIsReplacedByAClearChoice() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            openLeaveSheetWith(GuardEngine.ReleaseOutcome.SENT,a)
            assertNull("no plain Cancel once the child may already be released",button("取消"))
            val stay=button("留在家長身份，之後重新配對子女手機")
            assertNotNull(stay)
            assertTrue(texts().any{it.contains("未設定")&&it.contains("重新配對")})
            stay!!.performClick();idle()
            assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(secret,prefs.getString("parent_master_password",null))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun anUncertainResetIsNotReportedAsDeliveredAndRepeatsThePasswordFallback() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            openLeaveSheetWith(GuardEngine.ReleaseOutcome.SENT_UNSURE,a)
            assertTrue(texts().any{it.contains("可能沒有收到")})
            assertFalse(texts().any{it.contains("已送到中繼站")})
            assertTrue(texts().count{it.contains(secret)}>=1&&texts().any{it.contains("輸入")&&it.contains("主密碼")})
        } finally {c.pause().stop().destroy()}
    }
    @Test fun aResetPostedToTheNewTokenDuringAPendingRotationIsClassifiedUncertain() {
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        val next=UUID.randomUUID().toString()
        assertEquals(GuardEngine.ReleaseOutcome.SENT,GuardEngine.classifyRelease(ctx,true))
        prefs.edit().putString("pending_rotation_json",JSONObject().put("old_token",old).put("new_token",next).toString()).putString("family_uuid_token",next).commit()
        assertEquals(GuardEngine.ReleaseOutcome.SENT_UNSURE,GuardEngine.classifyRelease(ctx,true))
        assertEquals(GuardEngine.ReleaseOutcome.NOT_SENT,GuardEngine.classifyRelease(ctx,false))
    }
    @Test fun aFailureAfterTheResetExplainsWhatHappenedAndWhatToDo() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            GuardEngine.releaseOverride={_,_->GuardEngine.ReleaseOutcome.SENT}
            call(a,"navigateTo","settings")
            all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
            button("秘密可能外洩：重新安全配對")!!.performClick()
            button("只建立新配對（不刪除中繼資料）")!!.performClick();idle()
            (latest() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            answer(a)
            TestKeyStore.failEcGeneration=true
            button("繼續")!!.performClick()
            waitFor("result"){button("我已記下，繼續")!=null}
            button("我已記下，繼續")!!.performClick();idle()
            val shown=ShadowDialog.getShownDialogs().filter{it.isShowing}.flatMap{d->all(d.window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}}
            assertTrue("the parent is told the child may already be released and what to do",shown.any{it.contains("可能已收到解除指令")&&it.contains("重試")})
            assertEquals("the family was not replaced",GuardEngine.getOrCreateFamilyToken(ctx),GuardEngine.getOrCreateFamilyToken(ctx))
        } finally {c.pause().stop().destroy()}
    }

    // ---- third review: "not sent" must be true ----
    @Test fun aResetThatWasNotAcceptedIsNotLeftQueuedToUnprotectTheChildLater() {
        val topic=GuardEngine.getChannelTopic(ctx)
        // As the UI calls it (background thread). The relay is unreachable in tests, so the publish fails within the wait.
        var outcome:GuardEngine.ReleaseOutcome?=null
        kotlin.concurrent.thread{outcome=GuardEngine.releaseChildBeforeLeaving(ctx,10_000)}.join()
        assertEquals(GuardEngine.ReleaseOutcome.NOT_SENT,outcome)
        assertEquals("nothing may remain queued for a reset reported as not sent",0,FamilyEvidence(ctx).use{it.pendingCount(topic)})
        GuardEngine.retryPendingAlerts(ctx,true)   // a later reconnect has nothing to deliver
        assertTrue(pendingWires(topic).isEmpty())
        assertEquals(0,FamilyEvidence(ctx).use{it.pendingCount(topic)})
    }
    /** A publish can take up to 16 s: when the wait ends first, the frame may already be with the relay. */
    @Test fun aResetStillInFlightAtTheTimeoutIsReportedSentNotConfirmedAndStaysQueued() {
        val topic=GuardEngine.getChannelTopic(ctx)
        val publishing=java.util.concurrent.CountDownLatch(1)
        GuardEngine.beforeDeliveryWorkerEndsForTest={publishing.await(20,java.util.concurrent.TimeUnit.SECONDS)}   // the publish is still running
        try {
            var outcome:GuardEngine.ReleaseOutcome?=null
            kotlin.concurrent.thread{outcome=GuardEngine.releaseChildBeforeLeaving(ctx,300)}.join()
            assertEquals("never \"not sent\" while the reset may be on its way","SENT_UNCONFIRMED",outcome!!.name)
            assertTrue("the reset stays queued, so \"sent, not confirmed\" stays true",FamilyEvidence(ctx).use{it.pendingCount(topic)}>0)
        } finally {publishing.countDown();GuardEngine.beforeDeliveryWorkerEndsForTest=null}
    }
    @Test fun anUnconfirmedResetSaysTheChildMayBeReleasedInBothLanguages() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            openLeaveSheetWith(GuardEngine.ReleaseOutcome.valueOf("SENT_UNCONFIRMED"),a)
            assertTrue(texts().any{it.contains("可能已送出")&&it.contains("may have been sent")})
            assertFalse(texts().any{it.contains("未能送出")})
            assertNull("no plain Cancel: the child may already be released",button("取消"))
        } finally {c.pause().stop().destroy()}
    }
    /** A sent or possibly sent release says it acts when the child next connects, up to 10 days, and the child still shows as monitored. */
    @Test fun aSentReleaseSaysItCanTakeUpToTenDaysInBothLanguages() {
        for (outcome in listOf(GuardEngine.ReleaseOutcome.SENT,GuardEngine.ReleaseOutcome.valueOf("SENT_UNCONFIRMED"))) {
            prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db");GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,secret)
            val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
            try {
                openLeaveSheetWith(outcome,a)
                val text=texts().first{it.contains("解除指令")&&it.contains("中繼")&&it.contains("未設定")}
                assertTrue(text,text.contains("下次連線")&&text.contains("10 天")&&text.contains("受監察"))
                assertTrue(text,text.contains("next comes online")&&text.contains("up to 10 days")&&text.contains("monitored"))
                assertTrue("the Chinese text comes first",text.indexOf("10 天")<text.indexOf("up to 10 days"))
            } finally {c.pause().stop().destroy()}
        }
    }

    // ---- a frame written to the relay's socket may have reached it ----
    /** A local relay: "accept" takes every publish; "after" closes the socket once a publish frame arrives; "before" closes before ready. */
    private class Relay(val mode:String):okhttp3.mockwebserver.Dispatcher() {
        val published=java.util.concurrent.atomic.AtomicBoolean()
        val server=okhttp3.mockwebserver.MockWebServer().also{it.dispatcher=this}
        override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object:okhttp3.WebSocketListener(){
            override fun onOpen(webSocket:okhttp3.WebSocket,response:okhttp3.Response){
                if(mode=="before")webSocket.close(1011,"gone") else webSocket.send("{\"type\":\"ready\",\"protocol\":1}")
            }
            override fun onMessage(webSocket:okhttp3.WebSocket,text:String){
                val p=JSONObject(text);if(p.optString("type")!="publish")return
                published.set(true)
                if(mode=="accept")webSocket.send(JSONObject().put("type","accepted").put("id",p.getString("id")).put("mode","buffered").toString())
                else webSocket.close(1011,"gone")
            }
            override fun onClosing(webSocket:okhttp3.WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        })
    }
    private fun <T> withRelay(mode:String,body:(Relay)->T):T {
        val relay=Relay(mode);relay.server.start();CloudRelay.disconnect();CloudRelay.endpoint=relay.server.url("/").toString()
        try{return body(relay)}finally{CloudRelay.disconnect();CloudRelay.endpoint=BuildConfig.RELAY_BASE_URL;relay.server.shutdown()}
    }
    private fun releaseNow():GuardEngine.ReleaseOutcome {
        var outcome:GuardEngine.ReleaseOutcome?=null
        kotlin.concurrent.thread{outcome=GuardEngine.releaseChildBeforeLeaving(ctx,10_000)}.join()
        return outcome!!
    }
    @Test fun aResetWrittenToTheSocketBeforeItClosedIsSentNotConfirmedAndStaysQueued() {
        val topic=GuardEngine.getChannelTopic(ctx)
        withRelay("after"){relay->
            val outcome=releaseNow()
            assertTrue("the publish frame reached the relay's socket",relay.published.get())
            assertEquals("the relay may hold it: never \"not sent\"",GuardEngine.ReleaseOutcome.SENT_UNCONFIRMED,outcome)
            assertTrue("the reset stays queued",FamilyEvidence(ctx).use{it.pendingCount(topic)}>0)
        }
    }
    @Test fun aResetThatNeverReachedTheSocketIsNotSentAndWithdrawn() {
        val topic=GuardEngine.getChannelTopic(ctx)
        withRelay("before"){relay->
            val outcome=releaseNow()
            assertFalse(relay.published.get())
            assertEquals(GuardEngine.ReleaseOutcome.NOT_SENT,outcome)
            assertEquals("withdrawn",0,FamilyEvidence(ctx).use{it.pendingCount(topic)})
        }
    }
    @Test fun theRelayResultSaysWhetherTheFrameWasWritten() {
        assertFalse("existing callers keep their meaning",CloudRelay.PublishResult(false,"x").written)
        for((mode,written) in listOf("after" to true,"before" to false)) withRelay(mode){relay->
            val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),GuardEngine.getOrCreateFamilyToken(ctx),relay.server.url("/").toString())
            val done=java.util.concurrent.CountDownLatch(1);var result:CloudRelay.PublishResult?=null
            val id="written-$mode:0";val wire=GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),"{}")
            FamilyEvidence(ctx).use{it.enqueue(GuardEngine.getChannelTopic(ctx),id,wire,role="parent",kind="control")}
            try{connection.publish(id,wire,"control"){result=it;done.countDown()};assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS))}finally{connection.close()}
            assertFalse(result!!.accepted);assertEquals(mode,written,result!!.written)
        }
    }

    // ---- the remote reset toast and the pairing wording ----
    @Test fun theRemoteResetToastSaysItCanTakeUpToTenDaysInBothLanguages() {
        withRelay("accept"){_->
            val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
            try {
                call(a,"sendParentCommand",true);idle();answer(a)
                waitFor("the sent toast"){org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.contains("指令已發送")==true}
                val text=org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
                assertTrue(text,text.contains("10 天")&&text.contains("受監察")&&text.contains("up to 10 days")&&text.contains("monitored"))
                assertTrue("Chinese first",text.indexOf("10 天")<text.indexOf("up to 10 days"))
            } finally {c.pause().stop().destroy()}
        }
    }
    @Test fun aSentReleaseSaysThePairingAgainIsByQrCode() {
        for (outcome in listOf(GuardEngine.ReleaseOutcome.SENT,GuardEngine.ReleaseOutcome.SENT_UNSURE)) {
            prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db");GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,secret)
            val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
            try {
                openLeaveSheetWith(outcome,a)
                val text=texts().first{it.startsWith("解除指令")}
                assertTrue(text,text.contains("QR 碼重新配對"));assertFalse(text,text.contains("用邀請"))
                if(outcome==GuardEngine.ReleaseOutcome.SENT){assertTrue(text,text.contains("with the QR code"));assertFalse(text,text.contains("invite"))}
            } finally {c.pause().stop().destroy()}
        }
    }

    // ---- recovery texts: what 6.0 really offers (QR pairing under "加入子女 · Add a child"), in both languages ----
    @Test fun theUncertainReleaseHasItsEnglishHalf() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            openLeaveSheetWith(GuardEngine.ReleaseOutcome.SENT_UNSURE,a)
            val text=texts().first{it.startsWith("解除指令")}
            assertTrue(text,text.contains(" / The release was sent")&&text.contains("may not have received it")&&text.contains("paired again by QR code"))
            assertTrue("Chinese first",text.indexOf("可能沒有收到")<text.indexOf("may not have received"))
        } finally {c.pause().stop().destroy()}
    }
    private fun openRecovery(a:MainActivity) {
        call(a,"navigateTo","settings")
        all(a.window.decorView).first{it.isClickable && it.contentDescription?.toString()?.startsWith("家長主密碼與金鑰")==true}.performClick()
        button("秘密可能外洩：重新安全配對")!!.performClick()
    }
    private fun shownTexts()=ShadowDialog.getShownDialogs().filter{it.isShowing}.flatMap{d->all(d.window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}}
    @Test fun aFailedRecoveryPointsToQrPairingUnderAddAChild() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            GuardEngine.releaseOverride={_,_->GuardEngine.ReleaseOutcome.SENT}
            openRecovery(a)
            button("只建立新配對（不刪除中繼資料）")!!.performClick();idle()
            (latest() as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle()
            answer(a)
            TestKeyStore.failEcGeneration=true
            button("繼續")!!.performClick()
            waitFor("result"){button("我已記下，繼續")!=null}
            button("我已記下，繼續")!!.performClick();idle()
            val text=shownTexts().first{it.contains("可能已收到解除指令")}
            assertTrue(text,text.contains("「加入子女」以 QR 碼重新配對")&&text.contains("by QR code under “Add a child”"))
            assertFalse(text,text.contains("邀請"))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun aFailedRoomDeletionPointsToQrPairingUnderAddAChild() {
        val server=okhttp3.mockwebserver.MockWebServer()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().setResponseCode(503)
        }
        server.start();CloudRelay.endpoint=server.url("/").toString()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            GuardEngine.releaseOverride={_,_->GuardEngine.ReleaseOutcome.SENT}
            openRecovery(a)
            button("嘗試刪除舊中繼房間，再建立新配對")!!.performClick();idle();answer(a)
            button("繼續")!!.performClick()
            waitFor("result"){button("我已記下，繼續")!=null}
            button("我已記下，繼續")!!.performClick()
            waitFor("the deletion toast"){org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.contains("子女手機如已解除")==true}
            val text=org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
            assertTrue(text,text.contains("「加入子女」以 QR 碼重新配對")&&text.contains("by QR code under “Add a child”"))
            assertFalse(text,text.contains("邀請"))
        } finally {c.pause().stop().destroy();CloudRelay.endpoint=BuildConfig.RELAY_BASE_URL;server.shutdown()}
    }
    @Test fun anUnconfirmedReleaseKeepsTheOldRoomSoTheReleaseCanStillArrive() {
        val server=okhttp3.mockwebserver.MockWebServer()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().setResponseCode(204)
        }
        server.start();CloudRelay.endpoint=server.url("/").toString()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val token=GuardEngine.getOrCreateFamilyToken(ctx)
            GuardEngine.releaseOverride={_,_->GuardEngine.ReleaseOutcome.SENT_UNCONFIRMED}
            openRecovery(a)
            button("嘗試刪除舊中繼房間，再建立新配對")!!.performClick();idle();answer(a)
            button("繼續")!!.performClick()
            waitFor("result"){button("我已記下，繼續")!=null}
            button("我已記下，繼續")!!.performClick()
            waitFor("the kept-room toast"){org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.contains("舊中繼房間未刪除")==true}
            Thread.sleep(500);idle()
            assertEquals("no DELETE may overtake the unconfirmed release",0,server.requestCount)
            assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("was not deleted"))
        } finally {c.pause().stop().destroy();CloudRelay.endpoint=BuildConfig.RELAY_BASE_URL;server.shutdown()}
    }
}
