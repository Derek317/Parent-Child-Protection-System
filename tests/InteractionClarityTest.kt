package app.family.igguard

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.net.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w320dp-h700dp-mdpi")
class InteractionClarityTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private var original:ProxySelector?=null
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1f)
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"parent")
        original=ProxySelector.getDefault();ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){ProxySelector.setDefault(original)}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun views(a:MainActivity):List<View>{
        for(i in 0..100){shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);if(all(a.window.decorView).none{it.tag=="evidence_loading"})break;Thread.sleep(10)}
        return all(a.window.decorView)
    }
    private fun nav(a:MainActivity,to:String)=MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,to)
    private fun button(a:MainActivity,title:String)=views(a).filterIsInstance<Button>().single{it.text.toString()==title}
    private fun thread(a:MainActivity,body:String="ordinary message"){
        val r=ConversationCapture.record(CapturedText("friend",body,"incoming",messageTime=System.currentTimeMillis()-1000),System.currentTimeMillis(),true,false).put("event_id","interaction-message")
        GuardEngine.recordParentAlert(ctx,r,"interaction-message")
        nav(a,"alerts");views(a).single{it.tag=="evidence_contact_friend"}.performClick();views(a)
    }
    @Test fun navigationRowsAnnounceButtonRoleAndOpenTheirAction(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get();nav(a,"settings")
        try {
            val row=views(a).single{it.isClickable && it.contentDescription?.toString()?.startsWith("連接與診斷。")==true}
            assertEquals("android.widget.Button",row.createAccessibilityNodeInfo().className.toString())
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null))
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
        }finally{c.stop().destroy()}
    }
    @Test fun roleCardsExposeTheirChoiceAndSelectionToAccessibility(){
        GuardEngine.setRole(ctx,"unconfigured")
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get();nav(a,"role")
        try {
            val choice=views(a).single{it.tag=="entry_parent"}
            assertTrue(choice.createAccessibilityNodeInfo().isCheckable)
            choice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null)
            assertTrue(views(a).single{it.tag=="entry_parent"}.createAccessibilityNodeInfo().isChecked)
            assertTrue(views(a).single{it.tag=="continue_role"}.isEnabled)
        }finally{c.stop().destroy()}
    }
    @Test fun messageDetailsHaveAVisibleCueAndOpenUntruncatedEvidence(){
        val body="long message ".repeat(40)+"FINAL EVIDENCE"
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get()
        try {
            thread(a,body);val bubble=views(a).single{it.tag=="evidence_interaction-message"}
            assertTrue(all(bubble).filterIsInstance<TextView>().any{it.text.toString()=="查看完整記錄"})
            assertEquals("android.widget.Button",bubble.createAccessibilityNodeInfo().className.toString())
            assertTrue(bubble.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null))
            assertTrue(all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().any{it.text.toString()==body})
        }finally{c.stop().destroy()}
    }
    @Test fun narrowLargeTextFiltersStayInAtMostTwoColumnsAndRemainOperable(){
        RuntimeEnvironment.setFontScale(1.5f)
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get()
        try {
            thread(a)
            val shell=MainActivity::class.java.getDeclaredField("screenShell").apply{isAccessible=true}.get(a) as View
            shell.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY));shell.layout(0,0,320,700)
            val controls=listOf("全部日期","只看已儲存","全部方向","內容分類").map{button(a,it)}
            val rows=controls.groupBy{v->IntArray(2).also{v.getLocationOnScreen(it)}[1]}
            assertTrue("Narrow filters must wrap into readable rows",rows.values.all{it.size<=2})
            controls.first().performClick()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,1,1)
            assertNotNull(button(a,"最近 7 日"))
            button(a,"只看已儲存").performClick()
            assertTrue(button(a,"已儲存 ✓").isSelected)
        }finally{c.stop().destroy()}
    }
    @Test fun shorterThreadIntroductionKeepsOrderingLimitationsAvailable(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get()
        try {
            thread(a);button(a,"排序與擷取說明").performClick()
            val text=all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().joinToString(" "){it.text}
            assertTrue(text.contains("通知時間與畫面順序可能無法互相對齊"))
            assertTrue(text.contains("未顯示或未擷取的訊息無法復原"))
        }finally{c.stop().destroy()}
    }
    @Test fun appearanceButtonsExposeTheCurrentTheme(){
        GuardEngine.setBrightTheme(ctx,true)
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get();nav(a,"settings")
        try {
            assertTrue(button(a,"淺色模式").createAccessibilityNodeInfo().isSelected)
            button(a,"深色模式").performClick()
            assertTrue(button(a,"深色模式").createAccessibilityNodeInfo().isSelected)
            assertFalse(button(a,"淺色模式").createAccessibilityNodeInfo().isSelected)
        }finally{c.stop().destroy()}
    }
}
