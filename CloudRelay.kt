package app.family.igguard

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

enum class RelayConsumption { CONSUMED, REJECTED, RETRY }

/** One authenticated session; SQLite on the phones owns unconfirmed evidence. */
object CloudRelay {
    data class PublishResult(val accepted:Boolean,val message:String,val contextFull:Boolean=false,val expired:Boolean=false)
    private const val ROOM_RETIRED="中繼房間已刪除或已停用 · 請兩部手機重新配對"
    private const val DAY_MS=24*60*60_000L
    private val executor=ScheduledThreadPoolExecutor(1){task->Thread(task,"ig-guard-relay").apply{isDaemon=true}}.apply{removeOnCancelPolicy=true}
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS)
        .pingInterval(60,TimeUnit.SECONDS).build()
    private var active:Connection?=null
    private var oldRoute:Connection?=null
    @Volatile private var parentAlert:((JSONObject)->Unit)?=null

    /** Parent-authorized erasure uses the old room proof; it carries no replacement pairing secrets. */
    fun retireRoom(ctx:Context, endpoint:String = BuildConfig.RELAY_BASE_URL, onComplete:(Boolean,String)->Unit) {
        val origin:Triple<String,String,Int>;val request:Request
        try {
            check(GuardEngine.isParentMode(ctx) && GuardEngine.transferAllowed(ctx))
            origin=GuardEngine.captureRelaySession(ctx)
            val scope=GuardEngine.getChannelTopicForToken(origin.second)
            val room=FamilyRelay.room(GuardEngine.relayPublicKey(ctx),scope)
            val socket=FamilyRelay.socketUrl(endpoint,room,BuildConfig.DEBUG)
            val url=socket.replaceFirst("wss://","https://").replaceFirst("ws://","http://")
            request=Request.Builder().url(url).delete().header("Authorization",GuardEngine.relayAuthorization(ctx,origin.second)).build()
        } catch(_:Exception){onComplete(false,"未能準備中繼刪除請求；目前配對保留。");return}
        executor.execute {
            if(GuardEngine.captureRelaySession(ctx)!=origin){onComplete(false,"配對已變更，刪除請求已停止。");return@execute}
            try {
                client.newBuilder().readTimeout(12,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    val success=response.code in 200..299
                    onComplete(success,when {
                        success -> "中繼已確認刪除及退役舊房間。"
                        // The current relay answers 426 before any state change: it simply cannot delete a room.
                        response.code in setOf(426,404,405,501) -> "目前的中繼不能刪除房間，原有配對已保留。可改選「只建立新配對」。"
                        else -> "中繼未確認刪除（HTTP ${response.code}）；目前配對保留。"
                    })
                }
            } catch(_:Exception){onComplete(false,"中繼未確認刪除；請檢查連線後重試。")}
        }
    }

    private fun connection(ctx:Context,token:String):Connection {
        check(GuardEngine.transferAllowed(ctx)) { "Consent and active transfer permission are required" }
        val session=GuardEngine.captureRelaySession(ctx)
        require(session.first!="unconfigured")
        if(token!=session.second)require(session.first=="parent")
        val temporary=token!=session.second
        val old=if(temporary)oldRoute else active
        if(old!=null && old.usable && old.origin==session && old.token==token)return old
        old?.close()
        val next=Connection(ctx.applicationContext,session,token,BuildConfig.RELAY_BASE_URL,temporary)
        if(temporary)oldRoute=next else active=next
        return next
    }
    fun connect(ctx:Context,onParentAlert:((JSONObject)->Unit)?=null) {
        executor.execute {
            if(onParentAlert!=null)parentAlert=onParentAlert
            if(!GuardEngine.transferAllowed(ctx) || GuardEngine.getRole(ctx)=="unconfigured")return@execute
            try{connection(ctx,GuardEngine.getOrCreateFamilyToken(ctx)).start()}
            catch(_:Exception){
                val status="中繼設定或家長驗證金鑰未完成 · 請更新兩部手機及配對資料"
                GuardEngine.cpLastPollStatus=status
                if(GuardEngine.isParentMode(ctx))GuardEngine.cpParentStreamState=status
            }
        }
    }
    fun disconnect() {executor.execute{
        active?.close();oldRoute?.close();active=null;oldRoute=null;parentAlert=null
    }
    }
    fun publishBlocking(ctx:Context,token:String,id:String,wire:String,kind:String):PublishResult {
        if(!AppConsent.hasAccepted(ctx))return PublishResult(false,"尚未同意使用條款，轉傳未啟用")
        val completed=CountDownLatch(1)
        var result=PublishResult(false,"尚未連線 · 加密記錄保留在手機")
        try {
            executor.execute {
                try{connection(ctx,token).publish(id,wire,kind){result=it;completed.countDown()}}
                catch(_:Exception){result=PublishResult(false,"中繼設定未完成或配對缺少家長驗證金鑰 · 請更新兩部手機及設定版本");completed.countDown()}
            }
            if(!completed.await(16,TimeUnit.SECONDS))return PublishResult(false,"等待中繼確認逾時 · 已保留，稍後重試")
        } catch(_:Exception){return PublishResult(false,"中繼設定未完成或配對缺少家長驗證金鑰 · 請更新兩部手機及設定版本")}
        return result
    }

    internal class Connection(
        private val ctx:Context,
        val origin:Triple<String,String,Int>,
        val token:String,
        endpoint:String,
        private val temporary:Boolean=false
    ) {
        private val publicKey=GuardEngine.relayPublicKey(ctx)
        private val room=FamilyRelay.room(publicKey,GuardEngine.getChannelTopicForToken(token))
        private val url=FamilyRelay.socketUrl(endpoint,room,BuildConfig.DEBUG)
        private val cooldownKey="relay_cooldown_${origin.first}_$room"
        private val prefs=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE)
        private var socket:WebSocket?=null
        private var ready=false
        @Volatile private var closed=false
        val usable:Boolean get()=current()
        private var reconnect:ScheduledFuture<*>?=null
        private var flush:ScheduledFuture<*>?=null
        private var failures=0
        private var lastPublish=0L
        private data class Pending(val json:JSONObject,val callback:(PublishResult)->Unit,var sent:Boolean=false,var timeout:ScheduledFuture<*>?=null)
        private val pending=linkedMapOf<String,Pending>()
        private fun current():Boolean {
            if(closed || !GuardEngine.transferAllowed(ctx) || GuardEngine.captureRelaySession(ctx)!=origin)return false
            return try{FamilyRelay.room(GuardEngine.relayPublicKey(ctx),GuardEngine.getChannelTopicForToken(token))==room}catch(_:Exception){false}
        }
        private fun state(text:String) {
            if(!current())return
            GuardEngine.cpLastPollStatus=text
            if(origin.first=="parent")GuardEngine.cpParentStreamState=text
            ctx.sendBroadcast(android.content.Intent(GuardEngine.ACTION_UI_REFRESH).setPackage(ctx.packageName))
        }
        fun start(){executor.execute{open()}}
        fun close(){executor.execute{stop()}}
        private fun stop() {
            closed=true;ready=false;reconnect?.cancel(false);flush?.cancel(false)
            socket?.close(1000,"Session ended");socket=null
            finishAll("原連線已停止；加密記錄仍由本機佇列保管")
        }
        fun publish(id:String,wire:String,kind:String,onComplete:(PublishResult)->Unit) {
            executor.execute {
                if(!current()){onComplete(PublishResult(false,"家庭或角色已變更，原傳送已停止"));return@execute}
                if(pending.size>=32 || pending.containsKey(id)){onComplete(PublishResult(false,"等待中的傳送較多 · 已保留，稍後重試"));return@execute}
                val item=Pending(JSONObject().put("type","publish").put("id",id).put("wire",wire).put("kind",kind),onComplete)
                pending[id]=item
                item.timeout=executor.schedule({
                    if(pending.remove(id)===item)item.callback(PublishResult(false,"中繼尚未確認 · 已保留，稍後重試"))
                    if(temporary && pending.isEmpty())stop()
                },15,TimeUnit.SECONDS)
                open();sendNext()
            }
        }
        private fun open() {
            if(!current()){stop();return}
            if(socket!=null || reconnect?.isDone==false)return
            val wait=prefs.getLong(cooldownKey,0)-System.currentTimeMillis()
            if(wait>0){schedule(wait);state("中繼暫緩 · 加密記錄保留，約 ${maxOf(1,wait/1000)} 秒後重試");return}
            try {
                val request=Request.Builder().url(url).header("Authorization",GuardEngine.relayAuthorization(ctx,token)).build()
                state("正在連接加密中繼 · 尚未代表家長收妥")
                socket=client.newWebSocket(request,object:WebSocketListener(){
                    override fun onMessage(ws:WebSocket,text:String){executor.execute{incoming(ws,text)}}
                    override fun onFailure(ws:WebSocket,error:Throwable,response:Response?){executor.execute{
                        if(socket!==ws){response?.close();return@execute}
                        val delay=if(response?.code in listOf(401,403))300_000L else response?.header("Retry-After")?.toLongOrNull()?.times(1000)
                        // A retired room stays retired for months: say so, and ask at most daily in case it was re-registered.
                        if(response?.code==410)fail(ROOM_RETIRED,DAY_MS)
                        else fail("連線未完成 · ${response?.code?.let{"HTTPS $it"}?:error.javaClass.simpleName} · 記錄保留",delay)
                        response?.close()
                    }}
                    override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,"")}
                    override fun onClosed(ws:WebSocket,code:Int,reason:String){executor.execute{if(socket===ws)fail("中繼已斷線 · 將重新連接")}}
                })
            } catch(_:Exception){fail("中繼授權或設定未完成",300_000L)}
        }
        private fun schedule(delay:Long) {
            reconnect?.cancel(false)
            reconnect=executor.schedule({reconnect=null;open()},delay.coerceIn(1000,24*60*60_000L),TimeUnit.MILLISECONDS)
        }
        private fun finishAll(text:String) {
            val items=pending.values.toList();pending.clear()
            for(item in items){item.timeout?.cancel(false);item.callback(PublishResult(false,text))}
        }
        private fun fail(text:String,serverDelay:Long?=null) {
            val old=socket;socket=null;ready=false;old?.cancel()
            val exponential=min(300_000L,2000L*(1L shl failures.coerceAtMost(8)))+Random.nextLong(1000)
            failures=(failures+1).coerceAtMost(8)
            val backoff=maxOf(serverDelay?.coerceIn(1000,24*60*60_000L) ?: 0L,exponential)
            prefs.edit().putLong(cooldownKey,System.currentTimeMillis()+backoff).commit()
            state(text);finishAll(text)
            if(current() && !temporary)schedule(backoff)
        }
        private fun sendNext() {
            if(!ready || !current())return
            while(true) {
                val item=pending.values.firstOrNull{!it.sent} ?: return
                val delay=100L-(System.currentTimeMillis()-lastPublish)
                if(delay>0){flush?.cancel(false);flush=executor.schedule({sendNext()},delay,TimeUnit.MILLISECONDS);return}
                // Cached frames may have expired or been discarded while awaiting socket readiness.
                val id=item.json.getString("id")
                val eligible=try{FamilyEvidence(ctx).use{it.pendingEvent(GuardEngine.getChannelTopicForToken(token),id)}}
                    catch(_:Exception){fail("未能核對本機待傳期限；傳送已停止。");return}
                if(!eligible) {
                    pending.remove(id);item.timeout?.cancel(false)
                    item.callback(PublishResult(false,"記錄已到期或已移除，不再傳送。"))
                    if(temporary && pending.isEmpty()){stop();return}
                    continue
                }
                item.sent=true;lastPublish=System.currentTimeMillis()
                if(socket?.send(item.json.toString())!=true){fail("中繼未能接收 · 已保留，稍後重試");return}
                if(pending.values.any{!it.sent})flush=executor.schedule({sendNext()},100,TimeUnit.MILLISECONDS)
                return
            }
        }
        private fun incoming(ws:WebSocket,text:String) {
            if(socket!==ws || !current())return
            try {
                require(text.toByteArray(Charsets.UTF_8).size<=8192)
                val message=JSONObject(text)
                when(message.optString("type")) {
                    "ready" -> {
                        require(message.optInt("protocol")==1)
                        ready=true;prefs.edit().remove(cooldownKey).commit()
                        state("加密中繼已連線 · 家長收妥需另行確認")
                        sendNext();if(!temporary)GuardEngine.retryPendingAlerts(ctx)
                    }
                    "accepted" -> {
                        require(message.optString("mode") in setOf("forwarded","buffered"))
                        val item=pending.remove(message.optString("id")) ?: return
                        failures=0
                        item.timeout?.cancel(false)
                        val status=if(message.optString("mode")=="buffered")"中繼已加密暫存 · 等待接收手機確認" else "中繼已轉送 · 等待接收手機確認"
                        if(temporary && pending.isEmpty())stop()
                        item.callback(PublishResult(true,status));sendNext()
                    }
                    "message" -> {
                        // Old-room rotation retries publish only; they must not import the old mailbox into the new scope.
                        if(temporary && token!=origin.second)return
                        require(message.optString("id").matches(Regex("[pc][0-3][a-f0-9]{64}")) && message.optString("digest").matches(Regex("[a-f0-9]{64}")))
                        require(message.opt("buffered") is Boolean && message.optString("wire").toByteArray(Charsets.UTF_8).size<4096)
                        require(message.optString("delivery_id").matches(Regex("[a-f0-9-]{36}")))
                        require(AlertFrames.digest(message.getString("wire").toByteArray(Charsets.UTF_8))==message.getString("digest"))
                        val outer=JSONObject().put("event","message").put("id",message.getString("delivery_id")).put("message",message.getString("wire"))
                        val result=GuardEngine.processIncomingRelayLine(ctx,outer.toString(),origin.first,parentAlert,token,origin.third)
                        if(result==RelayConsumption.RETRY){fail("本機尚未完成儲存 · 中繼記錄保留並重試");return}
                        // A committed rotation/reset may change the current session; consume on THIS old socket first.
                        if(message.getBoolean("buffered"))ws.send(JSONObject().put("type","consume").put("id",message.getString("id")).put("digest",message.getString("digest")).toString())
                        if(!current())stop()
                    }
                    "peer" -> if(message.optBoolean("online")){ws.send("{\"type\":\"replay\"}");GuardEngine.retryPendingAlerts(ctx)}
                    "retired" -> fail(ROOM_RETIRED,DAY_MS)
                    "error" -> {
                        val code=message.optString("code").takeIf{it.matches(Regex("[a-z_]{1,40}"))}?:"relay_unavailable"
                        val id=message.optString("id")
                        val item=pending[id]
                        // The relay will never take this id again. Keep the record, back it off, and let later alerts through.
                        if(code=="expired" && item!=null) {
                            pending.remove(id);item.timeout?.cancel(false)
                            item.callback(PublishResult(false,"中繼已不再接受這項逾期記錄 · 其他提醒仍可傳送",expired=true))
                            sendNext();return
                        }
                        if(code=="queue_full" && item?.json?.optString("kind")=="data") {
                            pending.remove(id);item.timeout?.cancel(false)
                            item.callback(PublishResult(false,"一般對話暫存已滿 · 已留在手機，緊急提醒仍可傳送",contextFull=true))
                            sendNext();return
                        }
                        val delay=message.optLong("retry_after_ms",60_000L).coerceIn(1000,24*60*60_000L)
                        fail("中繼暫緩：$code · 記錄保留，稍後重試",delay)
                    }
                }
            } catch(_:Exception){fail("中繼資料未能驗證 · 加密記錄保留")}
        }
    }
}
