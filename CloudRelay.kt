package app.family.igguard

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

enum class RelayConsumption { CONSUMED, REJECTED, RETRY, CLOCK_BEHIND }

/** One authenticated session; SQLite on the phones owns unconfirmed evidence. */
object CloudRelay {
    /** [written]: the publish frame was handed to the WebSocket (send returned true) before this failure, so the relay may hold it. */
    data class PublishResult(val accepted:Boolean,val message:String,val contextFull:Boolean=false,val expired:Boolean=false,val written:Boolean=false)
    private const val ROOM_RETIRED="中繼房間已刪除或已停用 · 請兩部手機重新配對"
    private const val DAY_MS=24*60*60_000L
    /** The relay asks a child that proves its key to come back in 15 s while its parent has not registered that key (409). */
    @Volatile internal var PAIRING_RETRY_MS=15_000L
    /** The relay base URL; tests point both phones at a local fake relay (debug builds allow http to localhost). */
    @Volatile internal var endpoint:String=BuildConfig.RELAY_BASE_URL
    private val executor=ScheduledThreadPoolExecutor(1){task->Thread(task,"ig-guard-relay").apply{isDaemon=true}}.apply{removeOnCancelPolicy=true}
    /** Room deletions (up to 15 s each) run here, one at a time in call order, so they never hold up sending and receiving. */
    private val retirer=Executors.newSingleThreadExecutor{task->Thread(task,"ig-guard-relay-retire").apply{isDaemon=true}}
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS)
        .pingInterval(60,TimeUnit.SECONDS).build()
    private var active:Connection?=null
    private var oldRoute:Connection?=null
    @Volatile private var parentAlert:((JSONObject)->Unit)?=null

    /** Parent-authorized erasure uses the old room proof; it carries no replacement pairing secrets. */
    fun retireRoom(ctx:Context, endpoint:String = this.endpoint, onComplete:(Boolean,String)->Unit) =
        retire(ctx,endpoint,null){code,message->onComplete(code in 200..299,message)}
    /** The same erasure for a QR pairing's rendezvous room (token S, v1 proof): on completion, cancel and expiry (design 2.5).
     *  [onComplete] gets true once nothing more can be done: 2xx (retired), 404 or 410 (no such room any more). Only a transient
     *  failure (network, 401, 408, 429, 5xx, a changed session, not allowed right now) gets false, so the token is kept for a retry. */
    internal fun retirePairingRoom(ctx:Context, token:String, endpoint:String = this.endpoint, onComplete:(Boolean)->Unit) =
        retire(ctx,endpoint,token){code,_->onComplete(code!=null && (code in 200..299 || code==404 || code==410))}
    /** [onResult] gets the HTTP status, or null when no answer came (not prepared, session changed, network). */
    private fun retire(ctx:Context, endpoint:String, token:String?, onResult:(Int?,String)->Unit) {
        val origin:Triple<String,String,Int>;val request:Request
        try {
            check(GuardEngine.isParentMode(ctx) && GuardEngine.transferAllowed(ctx))
            origin=GuardEngine.captureRelaySession(ctx)
            val scope=GuardEngine.getChannelTopicForToken(token ?: origin.second)
            val room=FamilyRelay.room(GuardEngine.relayPublicKey(ctx),scope)
            val socket=FamilyRelay.socketUrl(endpoint,room,BuildConfig.DEBUG)
            val url=socket.replaceFirst("wss://","https://").replaceFirst("ws://","http://")
            request=Request.Builder().url(url).delete().header("Authorization",GuardEngine.relayAuthorization(ctx,token ?: origin.second)).build()
        } catch(_:Exception){onResult(null,"未能準備中繼刪除請求；目前配對保留。");return}
        retirer.execute {
            if(GuardEngine.captureRelaySession(ctx)!=origin){onResult(null,"配對已變更，刪除請求已停止。");return@execute}
            try {
                client.newBuilder().readTimeout(12,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    val success=response.code in 200..299
                    onResult(response.code,when {
                        success -> "中繼已確認刪除及退役舊房間。"
                        // The current relay answers 426 before any state change: it simply cannot delete a room.
                        response.code in setOf(426,404,405,501) -> "目前的中繼不能刪除房間，原有配對已保留。可改選「只建立新配對」。"
                        else -> "中繼未確認刪除；目前配對保留。"
                    })
                }
            } catch(_:Exception){onResult(null,"中繼未確認刪除；請檢查連線後重試。")}
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
        val next=Connection(ctx.applicationContext,session,token,endpoint,temporary)
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
    /** The "立即重試傳送" button: reconnect the active family now instead of after the client back-off (bug-review B-8). */
    fun retryNow(ctx:Context) {
        executor.execute {
            if(!GuardEngine.transferAllowed(ctx) || GuardEngine.getRole(ctx)=="unconfigured")return@execute
            try{connection(ctx,GuardEngine.getOrCreateFamilyToken(ctx)).retryNow()}catch(_:Exception){}
        }
    }
    fun disconnect() {GuardEngine.cancelOutboxRetryWake();executor.execute{
        active?.close();oldRoute?.close();active=null;oldRoute=null;parentAlert=null
    }
    }
    /**
     * A short-lived QR pairing room beside the family connection (design 2.5): the rendezvous room (v1 proofs, token S) or the
     * new family room before the child has stored T1. Records are decrypted under [token] and handed to [onRecord]; nothing
     * touches the family outbox or the family state. [onRefused] gets 401/403/410 or a
     * `retired` notice (the pairing is over); other failures reconnect with the relay's Retry-After (15 s for 409). Callbacks run on the relay thread.
     */
    internal class PairingRoute(val publicKey:String,val token:String,val authorization:()->String,
        val onReady:(JSONObject)->Unit,val onRecord:(JSONObject)->Unit,val onRefused:(Int)->Unit)
    internal fun openPairingRoom(ctx:Context,route:PairingRoute,endpoint:String=this.endpoint):Connection =
        Connection(ctx.applicationContext,Triple(GuardEngine.getRole(ctx),"",0),route.token,endpoint,route=route).also{it.start()}

    fun publishBlocking(ctx:Context,token:String,id:String,wire:String,kind:String):PublishResult {
        if(!AppConsent.hasAccepted(ctx))return PublishResult(false,"尚未同意使用條款，轉傳未啟用")
        val completed=CountDownLatch(1)
        var result=PublishResult(false,"尚未連線 · 加密記錄保留在手機")
        try {
            executor.execute {
                try{connection(ctx,token).publish(id,wire,kind){result=it;completed.countDown()}}
                catch(_:Exception){result=PublishResult(false,"中繼設定未完成或配對缺少家長驗證金鑰 · 請更新兩部手機及設定版本");completed.countDown()}
            }
            // The connection never answered: whether the frame was written is unknown, so it counts as written.
            if(!completed.await(16,TimeUnit.SECONDS))return PublishResult(false,"等待中繼確認逾時 · 已保留，稍後重試",written=true)
        } catch(_:Exception){return PublishResult(false,"中繼設定未完成或配對缺少家長驗證金鑰 · 請更新兩部手機及設定版本")}
        return result
    }

    internal class Connection(
        private val ctx:Context,
        val origin:Triple<String,String,Int>,
        val token:String,
        endpoint:String,
        private val temporary:Boolean=false,
        private val route:PairingRoute?=null
    ) {
        private val publicKey=route?.publicKey ?: GuardEngine.relayPublicKey(ctx)
        private val room=FamilyRelay.room(publicKey,GuardEngine.getChannelTopicForToken(token))
        private val url=FamilyRelay.socketUrl(endpoint,room,BuildConfig.DEBUG)
        // A pairing room lives for minutes: its back-off stays in memory instead of the family's preferences.
        private val cooldownKey=if(route==null)"relay_cooldown_${origin.first}_$room" else null
        private var pairingCooldown=0L
        private val prefs=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE)
        private fun cooldownUntil()=cooldownKey?.let{prefs.getLong(it,0)} ?: pairingCooldown
        private fun setCooldown(until:Long){ if(cooldownKey!=null)prefs.edit().putLong(cooldownKey,until).commit() else pairingCooldown=until }
        private fun clearCooldown(){ if(cooldownKey!=null)prefs.edit().remove(cooldownKey).commit() else pairingCooldown=0L }
        private var socket:WebSocket?=null
        private var ready=false
        @Volatile private var closed=false
        val usable:Boolean get()=current()
        private var reconnect:ScheduledFuture<*>?=null
        private var flush:ScheduledFuture<*>?=null
        private var failures=0
        private var lastPublish=0L
        private data class Pending(val json:JSONObject,val callback:(PublishResult)->Unit,var sent:Boolean=false,var timeout:ScheduledFuture<*>?=null,var written:Boolean=false)
        private val pending=linkedMapOf<String,Pending>()
        private fun current():Boolean {
            // A pairing room is not the family session: it ends when its pairing ends (close) or consent is withdrawn.
            if(route!=null)return !closed && AppConsent.hasAccepted(ctx)
            if(closed || !GuardEngine.transferAllowed(ctx) || GuardEngine.captureRelaySession(ctx)!=origin)return false
            return try{FamilyRelay.room(GuardEngine.relayPublicKey(ctx),GuardEngine.getChannelTopicForToken(token))==room}catch(_:Exception){false}
        }
        private fun state(text:String) {
            if(route!=null || !current())return
            GuardEngine.cpLastPollStatus=text
            if(origin.first=="parent")GuardEngine.cpParentStreamState=text
            ctx.sendBroadcast(android.content.Intent(GuardEngine.ACTION_UI_REFRESH).setPackage(ctx.packageName))
        }
        fun start(){executor.execute{open()}}
        /** "Retry now": a person asked, so the client's own back-off (at most about 5 minutes) is dropped once.
         *  A longer delay came from the relay (Retry-After, retired room) and stays. */
        fun retryNow(){executor.execute{
            if(socket!=null || cooldownUntil()-System.currentTimeMillis()>301_000L)return@execute
            clearCooldown();reconnect?.cancel(false);reconnect=null;open()
        }}
        fun close(){executor.execute{stop()}}
        /** The parent's own family connection: its continuous readiness gates the records-not-delivered warning. */
        private val parentFamily=route==null && !temporary && origin.first=="parent"
        private fun stop() {
            if(parentFamily)GuardEngine.parentRelayReady(false)
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
                    if(pending.remove(id)===item)item.callback(PublishResult(false,"中繼尚未確認 · 已保留，稍後重試",written=item.written))
                    if(temporary && pending.isEmpty())stop()
                },15,TimeUnit.SECONDS)
                open();sendNext()
            }
        }
        private fun open() {
            if(!current()){stop();return}
            if(socket!=null || reconnect?.isDone==false)return
            val wait=cooldownUntil()-System.currentTimeMillis()
            if(wait>0){schedule(wait);state("中繼暫緩 · 加密記錄保留，約 ${maxOf(1,wait/1000)} 秒後重試");return}
            try {
                val request=Request.Builder().url(url).header("Authorization",route?.authorization?.invoke() ?: GuardEngine.relayAuthorization(ctx,token)).build()
                state("正在連接加密中繼 · 尚未代表家長收妥")
                // Only a child that proves its own key (v2) can be told apart: 409 = its parent has not registered the key yet.
                val keyedChild=route==null && origin.first=="child" && GuardEngine.childKeyAlias(ctx)!=null
                socket=client.newWebSocket(request,object:WebSocketListener(){
                    override fun onMessage(ws:WebSocket,text:String){executor.execute{incoming(ws,text)}}
                    override fun onFailure(ws:WebSocket,error:Throwable,response:Response?){
                        val staleTime=response?.code==401 && try{JSONObject(response.peekBody(1024).string()).optJSONObject("error")?.optString("code")=="stale_time"}catch(_:Exception){false}
                        executor.execute{
                        if(socket!==ws){response?.close();return@execute}
                        val code=response?.code
                        val delay=if(code in listOf(401,403))300_000L else response?.header("Retry-After")?.toLongOrNull()?.times(1000)
                        val clockOff=code==401 && response?.headers?.getDate("Date")?.let{GuardEngine.noteRelayClock(ctx,it.time)}==true
                        when {
                            clockOff -> fail("手機時間與中繼相差超過 2 分鐘 · 請檢查手機的日期和時間 · 已按中繼時間重試",5_000L,exact=true)
                            staleTime && route==null -> fail("中繼表示手機時間不正確 · 請檢查手機的日期和時間",delay)
                            route!=null && code in listOf(401,403,410) -> {stop();route.onRefused(code!!)}
                            (route!=null || keyedChild) && code==409 -> fail("正在等待家長手機完成配對 · 15 秒後重試",PAIRING_RETRY_MS,exact=true)
                            // Its key is not the one registered for this locked room: nothing is wiped, the person is told.
                            keyedChild && code==401 -> {GuardEngine.cpChildKeyRefused=true;fail("中繼未接受此手機的配對金鑰 · 需要重新配對",delay)}
                            // A retired room stays retired for months: say so, and ask at most daily in case it was re-registered.
                            code==410 -> fail(ROOM_RETIRED,DAY_MS)
                            else -> fail("連線未完成 · ${when(code){null->"網絡未能連線";401,403->"中繼未接受此配對的驗證";else->"中繼暫時未能回應"}} · 記錄保留",delay)
                        }
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
            for(item in items){item.timeout?.cancel(false);item.callback(PublishResult(false,text,written=item.written))}
        }
        /** [exact]: wait exactly [serverDelay] (the relay's fixed 15 s for a key not registered yet), not the growing back-off. */
        private fun fail(text:String,serverDelay:Long?=null,exact:Boolean=false) {
            if(parentFamily)GuardEngine.parentRelayReady(false)
            val old=socket;socket=null;ready=false;old?.cancel()
            val exponential=min(300_000L,2000L*(1L shl failures.coerceAtMost(8)))+Random.nextLong(1000)
            failures=(failures+1).coerceAtMost(8)
            val backoff=if(exact && serverDelay!=null)serverDelay else maxOf(serverDelay?.coerceIn(1000,24*60*60_000L) ?: 0L,exponential)
            setCooldown(System.currentTimeMillis()+backoff)
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
                val eligible=route!=null || try{FamilyEvidence(ctx).use{it.pendingEvent(GuardEngine.getChannelTopicForToken(token),id)}}
                    catch(_:Exception){fail("未能核對本機待傳期限；傳送已停止。");return}
                if(!eligible) {
                    pending.remove(id);item.timeout?.cancel(false)
                    item.callback(PublishResult(false,"記錄已到期或已移除，不再傳送。"))
                    if(temporary && pending.isEmpty()){stop();return}
                    continue
                }
                item.sent=true;lastPublish=System.currentTimeMillis()
                if(socket?.send(item.json.toString())!=true){fail("中繼未能接收 · 已保留，稍後重試");return}
                item.written=true
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
                        ready=true;failures=0;clearCooldown()
                        if(route==null)GuardEngine.cpChildKeyRefused=false
                        if(parentFamily)GuardEngine.parentRelayReady(true)
                        state("加密中繼已連線 · 家長收妥需另行確認")
                        if(route!=null){route.onReady(message);sendNext();return}
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
                        if(route!=null) {
                            // Only a pairing record (M1/M2/M3) that decrypts under the pairing token goes on and is consumed. Anything
                            // else stays at the relay untouched: a parent command queued in the new family room is redelivered to the
                            // child's own family connection once the pairing is stored (a new socket starts with nothing in flight).
                            val record=GuardEngine.decryptPayload(route.token,message.getString("wire"))?.takeIf{Pairing.isPairingRecord(it)} ?: return
                            route.onRecord(record)
                            if(message.getBoolean("buffered"))ws.send(JSONObject().put("type","consume").put("id",message.getString("id")).put("digest",message.getString("digest")).toString())
                            return
                        }
                        val outer=JSONObject().put("event","message").put("id",message.getString("delivery_id")).put("message",message.getString("wire"))
                        val result=GuardEngine.processIncomingRelayLine(ctx,outer.toString(),origin.first,parentAlert,token,origin.third)
                        if(result==RelayConsumption.RETRY){fail("本機尚未完成儲存 · 中繼記錄保留並重試");return}
                        if(result==RelayConsumption.CLOCK_BEHIND){fail("家長指令的時間比此手機快超過 2 分鐘 · 請檢查手機的日期和時間 · 指令保留在中繼，稍後重試");return}
                        // A committed rotation/reset may change the current session; consume on THIS old socket first.
                        if(message.getBoolean("buffered"))ws.send(JSONObject().put("type","consume").put("id",message.getString("id")).put("digest",message.getString("digest")).toString())
                        if(!current())stop()
                    }
                    "peer" -> if(message.optBoolean("online")){ws.send("{\"type\":\"replay\"}");if(route==null)GuardEngine.retryPendingAlerts(ctx)}
                    "retired" -> if(route!=null){stop();route.onRefused(410)} else fail(ROOM_RETIRED,DAY_MS)
                    "error" -> {
                        val code=message.optString("code").takeIf{it.matches(Regex("[a-z_]{1,40}"))}?:"relay_unavailable"
                        val id=message.optString("id")
                        val item=pending[id]
                        // The relay will never take this id again. Keep the record, back it off, and let later alerts through.
                        // A late reply for an id that already timed out must not drop the socket under other publishes.
                        if(code=="expired") {
                            if(item!=null) {
                                pending.remove(id);item.timeout?.cancel(false)
                                item.callback(PublishResult(false,"中繼已不再接受這項逾期記錄 · 其他提醒仍可傳送",expired=true))
                            }
                            if(temporary && pending.isEmpty())stop() else sendNext()
                            return
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
