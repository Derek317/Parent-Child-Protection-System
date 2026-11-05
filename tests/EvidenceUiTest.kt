package app.family.igguard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class EvidenceUiTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit();GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString());GuardEngine.setRole(ctx,"parent")}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun views(a:MainActivity):List<View> {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        var current=all(a.window.decorView)
        while(current.any{it.tag=="evidence_loading"} && System.nanoTime()<deadline){
            shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);current=all(a.window.decorView)
        }
        return current
    }
    private fun nav(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"alerts")
    private fun event(i:Int,sender:String="person",text:String="captured text $i",reason:String="stranger_message")=JSONObject().put("type","alert").put("event_id","event-$i").put("sender",sender).put("text",text).put("reason",reason).put("ts_ms",System.currentTimeMillis()+i)
    @Test fun contactListIsPagedAndSearchFindsOlderEvidence(){
        for(i in 0..65)GuardEngine.recordParentAlert(ctx,event(i,"person-$i",if(i==0)"oldneedle" else "ordinary"),"event-$i")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        assertEquals(30,views(a).count{it.tag?.toString()?.startsWith("evidence_contact_")==true})
        views(a).filterIsInstance<EditText>().single{it.tag=="evidence_search"}.setText("oldneedle")
        shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS)
        assertEquals(1,views(a).count{it.tag?.toString()?.startsWith("evidence_contact_")==true})
        assertTrue(views(a).filterIsInstance<TextView>().any{it.text.toString().contains("person-0")})
        c.destroy()
    }
    @Test fun legacyInboxMigratesWhenOpeningAlertsDirectly(){
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_alerts_json","[${event(1)}]").commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).create();nav(c.get())
        assertTrue(views(c.get()).any{it.tag=="evidence_contact_person"})
        c.destroy()
    }
    @Test fun contactDetailShowsPreviewButSheetKeepsCompleteTextAndSavedFlag(){
        val full="很長的訊息🙂".repeat(200)
        GuardEngine.recordParentAlert(ctx,event(1,text=full),"event-1")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        views(a).single{it.tag=="evidence_contact_person"}.performClick()
        assertTrue(views(a).filterIsInstance<TextView>().none{it.text.toString()==full})
        (views(a).find{it.tag=="evidence_event-1"} ?: error("Missing event; tags="+views(a).mapNotNull{it.tag})).performClick()
        val d=ShadowDialog.getLatestDialog();val sheet=all(d.window!!.decorView)
        assertTrue(sheet.filterIsInstance<TextView>().any{it.text.toString()==full && it.isTextSelectable})
        sheet.filterIsInstance<Button>().single{it.text.toString()=="儲存此訊息"}.performClick()
        FamilyEvidence(ctx).use{assertTrue(it.find(GuardEngine.getChannelTopic(ctx),"event-1")!!.getBoolean("_saved"))}
        c.destroy()
    }
    @Test fun adviceIsShownOnceForNewKeywordAlertsAndCanBeReopenedFromDetails(){
        GuardEngine.recordParentAlert(ctx,event(1,reason="sexual_or_grooming_content"),"event-1")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        MainActivity::class.java.getDeclaredField("activityVisible").apply{isAccessible=true}.setBoolean(a,true)
        val method=MainActivity::class.java.getDeclaredMethod("maybeShowSafetyAdvice").apply{isAccessible=true}
        method.invoke(a)
        val d=ShadowDialog.getLatestDialog()
        assertTrue(all(d.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("社工")})
        FamilyEvidence(ctx).use{assertNull(it.nextAdvice(GuardEngine.getChannelTopic(ctx)))}
        d.dismiss();method.invoke(a);assertFalse(d.isShowing)
        c.destroy()
    }
    @Test fun searchAndSelectedContactSurviveRecreation(){
        GuardEngine.recordParentAlert(ctx,event(1),"event-1")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        views(a).single{it.tag=="evidence_contact_person"}.performClick()
        views(a).filterIsInstance<EditText>().single{it.tag=="evidence_search"}.setText("captured")
        val saved=Bundle();c.saveInstanceState(saved);c.destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java).create(saved)
        nav(restored.get())
        assertEquals("captured",views(restored.get()).filterIsInstance<EditText>().single{it.tag=="evidence_search"}.text.toString())
        assertTrue("Restored tags: "+views(restored.get()).mapNotNull{it.tag},views(restored.get()).any{it.tag=="evidence_event-1"})
        restored.destroy()
    }
    @Test fun notificationsHavePrivateContentAndDistinctEvidenceLinks(){
        val service=Robolectric.buildService(ParentAlertService::class.java).create()
        val channels=ParentAlertService::class.java.getDeclaredMethod("ensureChannels").apply{isAccessible=true};channels.invoke(service.get())
        val notify=ParentAlertService::class.java.getDeclaredMethod("fireNativeParentNotification",JSONObject::class.java).apply{isAccessible=true}
        for(i in 1..2){val row=event(i,text="private secret $i",reason="sexual_or_grooming_content");GuardEngine.recordParentAlert(ctx,row,"event-$i");notify.invoke(service.get(),row)}
        val notifications=shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).allNotifications
        assertEquals(2,notifications.size)
        assertTrue(notifications.all{it.visibility==android.app.Notification.VISIBILITY_PRIVATE})
        assertTrue(notifications.none{it.extras.toString().contains("private secret")})
        assertNotEquals(shadowOf(notifications[0].contentIntent).savedIntent.data,shadowOf(notifications[1].contentIntent).savedIntent.data)
        service.destroy()
    }
    @Test fun currentNotificationCanOpenItsArchivedEvidence(){
        GuardEngine.recordParentAlert(ctx,event(1),"event-1")
        val scope=GuardEngine.getChannelTopic(ctx)
        val intent=Intent("app.family.igguard.OPEN_EVIDENCE",Uri.parse("igguard://evidence/$scope/event-1"))
        val c=Robolectric.buildActivity(MainActivity::class.java,intent).create()
        assertEquals("alerts",MainActivity::class.java.getDeclaredField("section").apply{isAccessible=true}.get(c.get()))
        c.destroy()
    }
    @Test fun viewedNotificationDoesNotReopenAfterRecreation(){
        GuardEngine.recordParentAlert(ctx,event(1,reason="sexual_or_grooming_content"),"event-1")
        val scope=GuardEngine.getChannelTopic(ctx)
        val intent=Intent("app.family.igguard.OPEN_EVIDENCE",Uri.parse("igguard://evidence/$scope/event-1"))
        val c=Robolectric.buildActivity(MainActivity::class.java,intent).create();val a=c.get()
        MainActivity::class.java.getDeclaredMethod("openPendingEvidence").apply{isAccessible=true}.invoke(a)
        val state=Bundle();c.saveInstanceState(state);val consumed=a.intent;c.destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java,consumed).create(state)
        assertNull(MainActivity::class.java.getDeclaredField("pendingEvidenceId").apply{isAccessible=true}.get(restored.get()))
        restored.destroy()
    }
    @Test fun staleNotificationCannotOpenAnotherFamiliesEvidence(){
        GuardEngine.recordParentAlert(ctx,event(1,reason="sexual_or_grooming_content"),"event-1")
        val oldScope=GuardEngine.getChannelTopic(ctx)
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString());GuardEngine.setRole(ctx,"parent")
        val intent=Intent("app.family.igguard.OPEN_EVIDENCE",Uri.parse("igguard://evidence/$oldScope/event-1"))
        val c=Robolectric.buildActivity(MainActivity::class.java,intent).create();nav(c.get())
        assertFalse(views(c.get()).any{it.tag=="evidence_event-1"})
        c.destroy()
    }
}
