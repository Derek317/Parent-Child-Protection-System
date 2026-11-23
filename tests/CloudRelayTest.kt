package app.family.igguard

import android.app.Application
import android.content.Context
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CloudRelayTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private lateinit var authority:FamilySecurity.SigningIdentity
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        authority=FamilySecurity.newSigningIdentity()
        LocalVault.preferences(ctx).edit().putString("parent_command_public_key",authority.publicKey).commit()
    }
    private fun publish(connection:CloudRelay.Connection,id:String,wire:String,kind:String,
        token:String=GuardEngine.getOrCreateFamilyToken(ctx),callback:(CloudRelay.PublishResult)->Unit) {
        FamilyEvidence(ctx).use{it.enqueue(GuardEngine.getChannelTopicForToken(token),id,wire,role=GuardEngine.getRole(ctx),kind=kind)}
        connection.publish(id,wire,kind,callback)
    }
    @Test fun realWebSocketPublishUsesFamilyCapabilityAndWaitsForServerAcceptance() {
        val server=MockWebServer();val sent=CountDownLatch(1);val completed=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onMessage(webSocket:WebSocket,text:String){
                val p=JSONObject(text)
                if(p.optString("type")=="publish") {assertEquals("event:0",p.getString("id"));assertEquals("urgent",p.getString("kind"));sent.countDown();webSocket.send("{\"type\":\"accepted\",\"id\":\"event:0\",\"mode\":\"buffered\"}")}
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),GuardEngine.getOrCreateFamilyToken(ctx),server.url("/").toString())
        try {
            var accepted=false
            publish(connection,"event:0",GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),"{\"type\":\"alert\"}"),"urgent"){accepted=it.accepted;completed.countDown()}
            assertTrue(sent.await(5,TimeUnit.SECONDS));assertTrue(completed.await(5,TimeUnit.SECONDS));assertTrue(accepted)
            val request=server.takeRequest(5,TimeUnit.SECONDS)!!
            val auth=JSONObject(String(java.util.Base64.getUrlDecoder().decode(request.getHeader("Authorization")!!.removePrefix("IGGuard "))))
            assertEquals("child",auth.getString("role"));assertEquals(FamilyRelay.childCapability(GuardEngine.getOrCreateFamilyToken(ctx)),auth.getString("capability"))
            assertFalse(request.path!!.contains(auth.getString("capability")))
        }finally{connection.close();server.shutdown()}
    }
    @Test fun aFailedLocalCommitNeverAcknowledgesBufferedPacketConsumption() {
        GuardEngine.setRole(ctx,"parent")
        FamilyEvidence(ctx).use{it.writableDatabase.execSQL("DROP TABLE evidence")}
        val server=MockWebServer();val closed=CountDownLatch(1);val consumed=AtomicInteger()
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val payload=JSONObject().put("type","alert").put("event_id","event").put("sender","contact").put("reason","stranger_message").put("text","preserve me").put("ts_ms",System.currentTimeMillis())
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){
                webSocket.send("{\"type\":\"ready\",\"protocol\":1}")
                val wire=GuardEngine.encryptPayload(token,payload.toString())
                webSocket.send(JSONObject().put("type","message").put("id","p3"+"a".repeat(64)).put("digest",AlertFrames.digest(wire.toByteArray())).put("delivery_id",UUID.randomUUID().toString()).put("buffered",true).put("wire",wire).toString())
            }
            override fun onMessage(webSocket:WebSocket,text:String){if(JSONObject(text).optString("type")=="consume")consumed.incrementAndGet()}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason);closed.countDown()}
            override fun onFailure(webSocket:WebSocket,error:Throwable,response:Response?){closed.countDown()}
        }));server.start()
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try{connection.start();assertTrue(closed.await(5,TimeUnit.SECONDS));assertEquals(0,consumed.get());assertTrue(GuardEngine.cpLastPollStatus.contains("本機"))}
        finally{connection.close();server.shutdown()}
    }

    @Test fun oldRotationRouteCanPublishWithoutConsumingTheOldMailboxIntoTheNewFamily() {
        GuardEngine.setRole(ctx,"parent")
        val oldToken=GuardEngine.getOrCreateFamilyToken(ctx)
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString(),preserveFamilyHistory=true)
        val oldBody=GuardEngine.encryptPayload(oldToken,JSONObject().put("type","alert").put("event_id","old-record").put("text","old room").toString())
        val completed=CountDownLatch(1);val consumed=AtomicInteger();var accepted=false
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onMessage(webSocket:WebSocket,text:String){
                val packet=JSONObject(text)
                if(packet.optString("type")=="consume")consumed.incrementAndGet()
                if(packet.optString("type")=="publish") {
                    webSocket.send(JSONObject().put("type","message").put("id","p3"+"a".repeat(64)).put("digest",AlertFrames.digest(oldBody.toByteArray())).put("delivery_id",UUID.randomUUID().toString()).put("buffered",true).put("wire",oldBody).toString())
                    webSocket.send("{\"type\":\"accepted\",\"id\":\"rotation:0\",\"mode\":\"buffered\"}")
                }
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),oldToken,server.url("/").toString(),temporary=true)
        try {
            publish(connection,"rotation:0",GuardEngine.encryptPayload(oldToken,"{}"),"control",token=oldToken){accepted=it.accepted;completed.countDown()}
            assertTrue(completed.await(5,TimeUnit.SECONDS));assertTrue(accepted);assertEquals(0,consumed.get())
            assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
        }finally{connection.close();server.shutdown()}
    }
    @Test fun aFullContextMailboxDoesNotDisconnectOrBlockAnUrgentAlert() {
        val server=MockWebServer();val contextDone=CountDownLatch(1);val urgentDone=CountDownLatch(1)
        var contextFull=false;var urgentAccepted=false
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onMessage(webSocket:WebSocket,text:String){
                val p=JSONObject(text)
                if(p.optString("type")=="publish")webSocket.send(if(p.getString("kind")=="data")
                    JSONObject().put("type","error").put("id",p.getString("id")).put("code","queue_full").put("retry_after_ms",30000).toString()
                    else JSONObject().put("type","accepted").put("id",p.getString("id")).put("mode","buffered").toString())
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            publish(connection,"context:0",GuardEngine.encryptPayload(token,"{}"),"data"){contextFull=it.contextFull;contextDone.countDown()}
            assertTrue(contextDone.await(5,TimeUnit.SECONDS));assertTrue(contextFull)
            publish(connection,"urgent:0",GuardEngine.encryptPayload(token,"{}"),"urgent"){urgentAccepted=it.accepted;urgentDone.countDown()}
            assertTrue(urgentDone.await(5,TimeUnit.SECONDS));assertTrue(urgentAccepted);assertEquals(1,server.requestCount)
        }finally{connection.close();server.shutdown()}
    }
    @Test fun shortServerHintsCannotCancelExponentialFailureBackoff() {
        val server=MockWebServer();val done=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onMessage(webSocket:WebSocket,text:String){webSocket.send("{\"type\":\"error\",\"code\":\"relay_unavailable\",\"retry_after_ms\":1000}")}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx);var completedAt=0L
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            publish(connection,"event:0",GuardEngine.encryptPayload(token,"{}"),"urgent"){completedAt=System.currentTimeMillis();done.countDown()}
            assertTrue(done.await(5,TimeUnit.SECONDS))
            val room=FamilyRelay.room(authority.publicKey,GuardEngine.getChannelTopic(ctx))
            val retryAt=LocalVault.preferences(ctx).getLong("relay_cooldown_child_$room",0)
            assertTrue("A short hint is a minimum, not permission to reset backoff",retryAt-completedAt>=1900)
        }finally{connection.close();server.shutdown()}
    }
    @Test fun anExpiredRecordDoesNotDisconnectOrBlockTheNextAlert() {
        val server=MockWebServer();val oldDone=CountDownLatch(1);val urgentDone=CountDownLatch(1)
        var expired=false;var oldAccepted=true;var urgentAccepted=false
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onMessage(webSocket:WebSocket,text:String){
                val p=JSONObject(text)
                if(p.optString("type")=="publish")webSocket.send(if(p.getString("id")=="old:0")
                    JSONObject().put("type","error").put("id","old:0").put("code","expired").put("retry_after_ms",0).toString()
                    else JSONObject().put("type","accepted").put("id",p.getString("id")).put("mode","buffered").toString())
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            publish(connection,"old:0",GuardEngine.encryptPayload(token,"{}"),"urgent"){expired=it.expired;oldAccepted=it.accepted;oldDone.countDown()}
            assertTrue(oldDone.await(5,TimeUnit.SECONDS));assertTrue(expired);assertFalse(oldAccepted)
            publish(connection,"urgent:0",GuardEngine.encryptPayload(token,"{}"),"urgent"){urgentAccepted=it.accepted;urgentDone.countDown()}
            assertTrue(urgentDone.await(5,TimeUnit.SECONDS));assertTrue(urgentAccepted)
            assertEquals("An expired record must not cost a reconnect",1,server.requestCount)
            assertTrue("The refused record stays on the phone",FamilyEvidence(ctx).use{it.pendingEvent(GuardEngine.getChannelTopicForToken(token),"old:0")})
        }finally{connection.close();server.shutdown()}
    }
    private fun awaitRetiredStatus():Boolean {
        val deadline=System.currentTimeMillis()+5000
        while(System.currentTimeMillis()<deadline){if(GuardEngine.cpLastPollStatus.contains("重新配對"))return true;Thread.sleep(20)}
        return false
    }
    private fun cooldown():Long = LocalVault.preferences(ctx).getLong("relay_cooldown_child_${FamilyRelay.room(authority.publicKey,GuardEngine.getChannelTopic(ctx))}",0)
    @Test fun retryNowDropsTheClientBackoffButNotARelayRequestedDelay() {
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val key="relay_cooldown_child_${FamilyRelay.room(authority.publicKey,GuardEngine.getChannelTopic(ctx))}"
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            // A retired room or a long Retry-After came from the relay: a tap does not override it.
            LocalVault.preferences(ctx).edit().putLong(key,System.currentTimeMillis()+DAY).commit()
            connection.start();connection.retryNow()
            assertNull(server.takeRequest(700,TimeUnit.MILLISECONDS));assertTrue(cooldown()>System.currentTimeMillis())
            // The client's own back-off (at most 5 minutes) is dropped and the relay is tried at once.
            LocalVault.preferences(ctx).edit().putLong(key,System.currentTimeMillis()+200_000L).commit()
            connection.retryNow()
            assertNotNull("retry now must reconnect without waiting",server.takeRequest(5,TimeUnit.SECONDS))
            assertEquals(0L,cooldown())
        }finally{connection.close();server.shutdown()}
    }
    private val DAY=24*60*60_000L
    @Test fun aRetiredRoomIsNamedPlainlyAndAskedAgainNoMoreThanDaily() {
        val server=MockWebServer()
        server.enqueue(MockResponse().setResponseCode(410).setHeader("Retry-After","60").setBody("{\"error\":{\"code\":\"room_retired\"}}"));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx);val started=System.currentTimeMillis()
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            connection.start()
            assertTrue(GuardEngine.cpLastPollStatus,awaitRetiredStatus())
            assertFalse(GuardEngine.cpLastPollStatus.contains("HTTPS 410"))
            // The server asked for 60 s: the day comes from the app, not from the header.
            assertTrue("A retired room is not retried within the day",cooldown()-started>=23*60*60_000L)
        }finally{connection.close();server.shutdown()}
    }
    @Test fun aRetiredFrameOnALiveSocketIsHandledLikeARetiredRoom() {
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}");webSocket.send("{\"type\":\"retired\"}")}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx);val started=System.currentTimeMillis()
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            connection.start()
            assertTrue(GuardEngine.cpLastPollStatus,awaitRetiredStatus())
            assertTrue(cooldown()-started>=23*60*60_000L)
        }finally{connection.close();server.shutdown()}
    }
    @Test fun aLateExpiredReplyForAnUnknownRecordDoesNotDropTheSocket() {
        val server=MockWebServer();val done=CountDownLatch(1);var accepted=false
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send("{\"type\":\"ready\",\"protocol\":1}")}
            override fun onMessage(webSocket:WebSocket,text:String){
                val p=JSONObject(text)
                if(p.optString("type")=="publish") {
                    webSocket.send("{\"type\":\"error\",\"id\":\"gone:0\",\"code\":\"expired\",\"retry_after_ms\":0}")
                    webSocket.send(JSONObject().put("type","accepted").put("id",p.getString("id")).put("mode","buffered").toString())
                }
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }));server.start()
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val connection=CloudRelay.Connection(ctx,GuardEngine.captureRelaySession(ctx),token,server.url("/").toString())
        try {
            publish(connection,"urgent:0",GuardEngine.encryptPayload(token,"{}"),"urgent"){accepted=it.accepted;done.countDown()}
            assertTrue(done.await(5,TimeUnit.SECONDS));assertTrue(accepted);assertEquals(1,server.requestCount)
        }finally{connection.close();server.shutdown()}
    }
}
