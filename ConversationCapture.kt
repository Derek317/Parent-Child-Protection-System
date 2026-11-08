package app.family.igguard

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** A displayed/notification observation, never a promise of a complete or delivered message. */
data class CapturedText(val conversation:String,val text:String,val direction:String,
    val author:String="",val account:String="",val source:String="visible_dm",val messageTime:Long=0,
    val sourceKey:String="",val possibleDuplicate:Boolean=false,val conversationKind:String="unknown",val labelFromToolbar:Boolean=false)
data class VisibleCaptureText(val text:String,val top:Int,val bottom:Int,val left:Int,val right:Int,
    val editable:Boolean=false,val fromText:Boolean=true,val children:Int=0,val direction:String="unknown",
    val viewId:String="",val button:Boolean=false,val heading:Boolean=false,
    val bubbleLeft:Int=left,val bubbleRight:Int=right)

object ConversationCapture {
    val contentTypes=setOf("alert","captured_message")
    val directions=setOf("incoming","outgoing","unknown")
    val sources=setOf("notification","visible_dm","comment_story_draft")
    val conversationKinds=setOf("direct","group","unknown")
    private val ordinary=setOf("heartbeat_ok","pairing_verified","test_alert","archive_recovery","conversation_context")
    private val secrecy=Regex("secret|don't tell|dont tell|唔好同",RegexOption.IGNORE_CASE)
    private val images=Regex("nudes?|send .*pic|send pic|裸照|私密照|閃卡|j圖|[咸鹹]相|拍片比我睇",RegexOption.IGNORE_CASE)
    private fun signalFor(phrase:String)=when{secrecy.containsMatchIn(phrase)->"secrecy";images.containsMatchIn(phrase)->"image_request";else->"sexual_language"}
    fun key(account:String,conversation:String,kind:String="unknown"):String=AlertFrames.digest(
        JSONArray().put(account.trim().removePrefix("@").lowercase(Locale.ROOT))
            .put(conversation.trim().removePrefix("@").lowercase(Locale.ROOT)).also{if(kind!="unknown")it.put(kind)}.toString().toByteArray(Charsets.UTF_8))
    fun actionable(record:JSONObject):Boolean=record.optString("reason") !in ordinary
    fun priority(record:JSONObject):Int=when {
        !actionable(record)->0
        record.optString("reason")=="tamper_attempt"->3
        record.optString("reason")=="sexual_or_grooming_content"->2
        else->1
    }
    fun record(c:CapturedText,now:Long,trusted:Boolean,flagged:Boolean):JSONObject {
        require(c.text.isNotBlank() && c.conversation.isNotBlank() && c.direction in directions && c.source in sources && c.conversationKind in conversationKinds)
        require(listOf(c.conversation,c.author,c.account).all{it.length<=256})
        val matches=JSONArray();val signals=linkedSetOf<String>()
        for((field,value) in listOf("text" to c.text,"author" to if(c.direction=="incoming")c.author else "")) {
            for(m in GuardEngine.SEXUAL_PATTERN.findAll(value).take(20)) {
                val tag=signalFor(m.value)
                signals.add(tag);matches.put(JSONObject().put("field",field).put("phrase",m.value).put("tag",tag))
            }
        }
        val reason=when {
            signals.isNotEmpty()->"sexual_or_grooming_content"
            c.source=="comment_story_draft"->"conversation_context"
            c.direction=="incoming" && flagged->"flagged_contact_follow_up"
            c.direction=="incoming" && !trusted->"stranger_message"
            else->"conversation_context"
        }
        if(reason=="stranger_message")signals.add("unapproved_contact")
        if(reason=="flagged_contact_follow_up")signals.add("flagged_follow_up")
        val stamp=c.messageTime.takeIf{it>0 && it<=now+120_000L}?:0L
        val conversation=c.conversation.trim()
        val r=JSONObject().put("type","captured_message").put("schema_version",2)
            .put("event_id",UUID.randomUUID().toString()).put("conversation_key",key(c.account,conversation,c.conversationKind))
            .put("conversation_kind",c.conversationKind)
            .put("conversation_label",conversation).put("sender",conversation)
            .put("account_label",c.account.trim()).put("author",c.author.trim()).put("direction",c.direction)
            .put("source_context",c.source).put("capture_source",c.source).put("text",c.text)
            .put("message_time_ms",stamp).put("captured_at_ms",now).put("capture_order",0L).put("ts_ms",if(stamp>0)stamp else now)
            .put("time","").put("trusted_at_capture",trusted).put("reason",reason)
            .put("signals",JSONArray(signals.toList())).put("matches",matches)
            .put("possible_duplicate",c.possibleDuplicate).put("flag_partner",c.conversationKind!="group" && c.direction=="incoming" && c.source!="comment_story_draft" && c.author.isNotBlank() &&
                c.author.trim().removePrefix("@").equals(conversation.removePrefix("@"),true) && signals.any{it in setOf("sexual_language","image_request","secrecy")})
        if(c.labelFromToolbar)r.put("label_from_toolbar",true)
        require(valid(r));return r
    }
    fun valid(r:JSONObject):Boolean {
        if(r.optString("type")!="captured_message" || r.optInt("schema_version")!=2)return false
        if(r.optString("direction") !in directions || r.optString("source_context") !in sources)return false
        if(!r.optString("conversation_key").matches(Regex("[a-f0-9]{64}")))return false
        val kind=r.optString("conversation_kind","unknown")
        if(kind !in conversationKinds || r.optString("conversation_key")!=key(r.optString("account_label"),r.optString("conversation_label"),kind))return false
        if(r.optString("conversation_label").isBlank() || r.optString("text").isBlank())return false
        if(listOf("conversation_label","author","account_label").any{r.optString(it).length>256})return false
        if(r.optString("reason") !in setOf("conversation_context","stranger_message","sexual_or_grooming_content","flagged_contact_follow_up"))return false
        if(!r.optString("event_id").matches(Regex("[A-Za-z0-9:_-]{1,160}")))return false
        if(r.optLong("captured_at_ms")<=0 || r.optLong("message_time_ms")<0)return false
        val messageTime=r.optLong("message_time_ms");val capturedTime=r.optLong("captured_at_ms")
        if(messageTime>capturedTime+120_000L || r.optLong("ts_ms")!=(if(messageTime>0)messageTime else capturedTime))return false
        if(r.optLong("capture_order")<0)return false
        if(r.has("screen_anchor_ms") || r.has("screen_order")) {
            if(!r.has("screen_anchor_ms") || !r.has("screen_order") || r.optString("source_context")!="visible_dm" || messageTime!=0L ||
                r.optLong("screen_anchor_ms") !in 1..capturedTime || r.optLong("screen_order") !in -1_000_000_000L..1_000_000_000L)return false
        }
        val signals=r.optJSONArray("signals")?:return false;val matches=r.optJSONArray("matches")?:return false
        if(signals.length()>8 || matches.length()>40)return false
        val allowed=setOf("secrecy","image_request","sexual_language","unapproved_contact","flagged_follow_up")
        if((0 until signals.length()).any{signals.opt(it) !is String || signals.optString(it) !in allowed})return false
        if((r.optString("reason")=="sexual_or_grooming_content") != (matches.length()>0))return false
        for(i in 0 until matches.length()) {
            val match=matches.optJSONObject(i)?:return false
            val field=match.optString("field");val phrase=match.optString("phrase")
            if(field !in setOf("text","author") || phrase.isBlank() || phrase.length>256 || !r.optString(field).contains(phrase,true) ||
                !GuardEngine.SEXUAL_PATTERN.containsMatchIn(phrase) || match.optString("tag")!=signalFor(phrase) ||
                (0 until signals.length()).none{signals.optString(it)==match.optString("tag")})return false
        }
        if(r.optBoolean("flag_partner") && (kind=="group" || r.optString("direction")!="incoming" || r.optString("source_context")=="comment_story_draft"))return false
        return r.toString().toByteArray(Charsets.UTF_8).size<=1024*1024
    }
    /** Longest bounded contiguous overlap; occurrences, rather than text alone, are retained. */
    fun overlap(previous:List<String>,current:List<String>):Triple<Int,Int,Int> {
        require(previous.size<=128 && current.size<=128)
        var best=0;var previousStart=0;var start=0
        for(i in previous.indices)for(j in current.indices) {
            var n=0
            while(i+n<previous.size && j+n<current.size && previous[i+n]==current[j+n])n++
            if(n>best){best=n;previousStart=i;start=j}
        }
        return Triple(previousStart,start,best)
    }
    fun newIndices(previous:List<String>,current:List<String>):List<Int> {
        val (_,start,best)=overlap(previous,current)
        return current.indices.filter{best==0 || it !in start until start+best}
    }
    private val navigationLabels=setOf("back","go back","返回","上一頁","返回上一頁","navigate up","call","video call","audio call","通話","語音通話","視像通話","視訊通話","more options","更多選項")
    fun navigationLabel(text:String)=text.trim().lowercase(Locale.ROOT) in navigationLabels
    fun unidentifiedLegacyLabel(record:JSONObject):Boolean=record.optString("source_context")!="notification" && record.optString("capture_source")!="notification" && !record.optBoolean("label_from_toolbar") &&
        navigationLabel(record.optString("conversation_label",record.optString("sender")))
    fun displayLabel(label:String,unidentified:Boolean)=if(unidentified)"未識別對話（舊擷取：$label）" else label
    fun messageRole(id:String):Boolean = id.lowercase(Locale.ROOT).let {
        (it.contains("message") || it.contains("row_thread_text") || it.contains("bubble")) &&
            listOf("composer","input","send_button","reaction","timestamp","status","seen","avatar","reply_button").none(it::contains)
    }
    fun semanticDirection(id:String):String {
        val name=id.lowercase(Locale.ROOT)
        if(!messageRole(name))return "unknown"
        return when {
            Regex("(^|_)(outgoing|sent)(_|$)").containsMatchIn(name.substringAfterLast('/'))->"outgoing"
            Regex("(^|_)(incoming|received)(_|$)").containsMatchIn(name.substringAfterLast('/'))->"incoming"
            else->"unknown"
        }
    }
    fun composerRole(id:String):Boolean = id.substringAfterLast('/').lowercase(Locale.ROOT) in
        setOf("message_composer","row_thread_composer_edittext","message_input","direct_text_input")
    fun composerLabel(text:String):Boolean = text.trim().trimEnd('.', '…', '⋯', ' ').lowercase(Locale.ROOT) in
        setOf("message","send message","訊息","消息","發送訊息","傳送訊息","輸入訊息","发送消息","发消息","输入消息")
    /** A toolbar title, not the first readable label (which is often the Back button). */
    fun threadHeader(items:List<VisibleCaptureText>,height:Int):VisibleCaptureText? {
        fun title(item:VisibleCaptureText)=Regex("(action_bar_title|thread_title|toolbar_title|header_title|thread_name|recipient_name)").containsMatchIn(item.viewId.lowercase(Locale.ROOT))
        val candidates=items.filter { !it.editable && (!it.button || title(it) || it.heading) && it.top>=0 && it.bottom<=height*.30 &&
            it.text.trim().length in 1..128 && !it.text.trim().all(Char::isDigit) &&
            (title(it) || !navigationLabel(it.text) && it.text.trim().lowercase(Locale.ROOT) !in GuardEngine.RESERVED_UI_HEADERS && !GuardEngine.isNonMessageUiString(it.text)) &&
            (it.fromText || title(it) || it.heading) && (it.children==0 || title(it) || it.heading) }
        val backs=items.filter{it.viewId.contains("button_back") || it.button && it.text.trim().lowercase(Locale.ROOT) in
            setOf("back","go back","返回","上一頁","返回上一頁","navigate up")}
        val row=candidates.filter{item->backs.any{back->item.top<back.bottom && item.bottom>back.top && item.left>=back.right}}
        val rowTitles=row.filter{title(it)}.distinctBy{it.text.trim()}
        if(rowTitles.isNotEmpty())return rowTitles.singleOrNull()
        val headings=row.filter{it.heading}.distinctBy{it.text.trim()}
        if(headings.isNotEmpty())return headings.singleOrNull()
        if(row.isNotEmpty())return row.distinctBy{it.text.trim()}.singleOrNull()
        return candidates.filter{title(it)}.distinctBy{it.text.trim()}.singleOrNull()
    }
    fun visibleMessages(items:List<VisibleCaptureText>,width:Int,height:Int,threadTop:Int,composerTop:Int):List<VisibleCaptureText> {
        if(width<=0 || height<=0 || composerTop<=threadTop)return emptyList()
        return items.asSequence().filter{!it.editable && !it.button && (it.fromText || messageRole(it.viewId)) && it.children==0 && it.text.isNotBlank() &&
            it.bottom>threadTop && it.top<minOf(composerTop,height) && it.right>0 && it.left<width &&
            !GuardEngine.NON_MESSAGE_UI_PATTERN.matches(it.text.trim())}
            .distinctBy{listOf(it.text,it.top,it.bottom,it.left,it.right)}.sortedWith(compareBy({it.top},{it.left})).take(128).map {
                val left=it.bubbleLeft.coerceAtLeast(0);val right=(width-it.bubbleRight).coerceAtLeast(0)
                val direction=when {
                    it.direction in setOf("incoming","outgoing")->it.direction
                    right-left>width*.035->"incoming"
                    left-right>width*.035->"outgoing"
                    else->"unknown"
                }
                it.copy(direction=direction)
            }.toList()
    }
}
