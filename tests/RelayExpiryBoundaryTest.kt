package app.family.igguard

import android.app.Application
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RelayExpiryBoundaryTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){
        LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db")
        GuardEngine.setRole(ctx,"parent")
    }
    private fun delayedReady(remove:Boolean) {
        val token=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx)
        val wire=GuardEngine.encryptPayload(token,"{}")
        FamilyEvidence(ctx).use{it.enqueue(scope,"late:0",wire,role="parent",kind="control")}
        val opened=CountDownLatch(1);val done=CountDownLatch(1)
        val peer=AtomicReference<WebSocket>();val publishes=AtomicInteger();var accepted=true
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){peer.set(webSocket);opened.countDown()}
            override fun onMessage(webSocket:WebSocket,text:String){
                val msg=JSONObject(text)
                if(msg.optString("type")=="publish"){
                    publishes.incrementAndGet()
                    webSocket.send(JSONObject().put("type","accepted").put("id",msg.getString("id")).put("mode","buffered").toString())
                }
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            connection.publish("late:0",wire,"control"){accepted=it.accepted;done.countDown()}
            assertTrue(opened.await(5,TimeUnit.SECONDS))
            FamilyEvidence(ctx).use{db->if(remove)db.discardPending(scope) else db.writableDatabase.execSQL("UPDATE outbox_lifetimes SET expires=1")}
            peer.get().send("{\"type\":\"ready\",\"protocol\":1}")
            assertTrue(done.await(5,TimeUnit.SECONDS));assertFalse("Expired or discarded frame must not be accepted",accepted)
            assertEquals("Cached ciphertext must not leave the socket",0,publishes.get())
        }finally{connection.close();server.shutdown()}
    }
    @Test fun expiryWhileWaitingForReadyPreventsSocketPublication(){delayedReady(false)}
    @Test fun localDiscardWhileWaitingForReadyPreventsSocketPublication(){delayedReady(true)}
}
