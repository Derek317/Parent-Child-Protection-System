package app.family.igguard

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Routing and relay authorization never disclose the family encryption token. */
object FamilyRelay {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private fun encoded(bytes:ByteArray) = encoder.encodeToString(bytes)
    @Volatile var clockOffsetMs = 0L
    private fun hash(text:String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}

    fun room(publicKey:String,scope:String):String {
        require(scope.matches(Regex("ig_hk_[a-f0-9]{28}")))
        return hash("igguard-relay-room-v1\n${encoded(decoder.decode(publicKey))}\n$scope")
    }
    fun childCapability(token:String):String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(token.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        encoded(doFinal("igguard-relay-child-v1".toByteArray(Charsets.UTF_8)))
    }
    fun capabilityHash(capability:String):String = hash(capability)

    fun parentAuthorization(identity:FamilySecurity.SigningIdentity,scope:String,token:String,now:Long=System.currentTimeMillis()+clockOffsetMs,nonce:String=UUID.randomUUID().toString()):String {
        val room=room(identity.publicKey,scope)
        val capabilityHash=capabilityHash(childCapability(token))
        val proof=listOf("igguard-relay-auth-v1","parent",room,scope,now.toString(),nonce,capabilityHash).joinToString("\n")
        val signature=encoded(FamilySecurity.signBytes(proof.toByteArray(Charsets.UTF_8),identity.privateKey))
        return authorization(JSONObject().put("v",1).put("role","parent").put("room",room).put("scope",scope)
            .put("public_key",encoded(decoder.decode(identity.publicKey))).put("capability_hash",capabilityHash)
            .put("ts_ms",now).put("nonce",nonce).put("signature",signature))
    }
    fun childAuthorization(publicKey:String,scope:String,token:String):String = authorization(JSONObject().put("v",1).put("role","child")
        .put("room",room(publicKey,scope)).put("scope",scope).put("capability",childCapability(token)))
    private fun authorization(value:JSONObject)="IGGuard "+encoded(value.toString().toByteArray(Charsets.UTF_8))

    /** A v2 parent proof registers or replaces the room's child key. [childKey] is the child's P-256 SPKI text; it is sent unpadded,
     * the form the relay compares byte for byte with the child's own v2 proof. The signature is DER (base64url), as in v1. */
    fun parentAuthorizationV2(identity:FamilySecurity.SigningIdentity,scope:String,token:String,childKey:String,now:Long=System.currentTimeMillis()+clockOffsetMs,nonce:String=UUID.randomUUID().toString()):String {
        require(PairingCrypto.validP256Key(childKey)) { "Invalid child key" }
        val room=room(identity.publicKey,scope)
        val capabilityHash=capabilityHash(childCapability(token))
        val child=encoded(decoder.decode(childKey))
        val proof=listOf("igguard-relay-auth-v2","parent",room,scope,now.toString(),nonce,capabilityHash,child).joinToString("\n")
        val signature=encoded(FamilySecurity.signBytes(proof.toByteArray(Charsets.UTF_8),identity.privateKey))
        return authorization(JSONObject().put("v",2).put("role","parent").put("room",room).put("scope",scope)
            .put("public_key",encoded(decoder.decode(identity.publicKey))).put("capability_hash",capabilityHash).put("child_key",child)
            .put("ts_ms",now).put("nonce",nonce).put("signature",signature))
    }
    /** A v2 child proof: the v1 capability plus a signature by [child]'s Keystore key over the room, time, nonce and sha256(capability).
     * [sign] returns a DER ECDSA-P256-SHA256 signature; it is injectable only so JVM vectors can sign with a raw test key. */
    fun childAuthorizationV2(publicKey:String,scope:String,token:String,child:FamilySecurity.ChildSigningKey,now:Long=System.currentTimeMillis()+clockOffsetMs,
        nonce:String=UUID.randomUUID().toString(),sign:(ByteArray)->ByteArray={FamilySecurity.signChildBytes(it,child.alias)}):String {
        val room=room(publicKey,scope)
        val capability=childCapability(token)
        val proof=listOf("igguard-relay-auth-v2","child",room,scope,now.toString(),nonce,capabilityHash(capability)).joinToString("\n")
        val signature=encoded(sign(proof.toByteArray(Charsets.UTF_8)))
        return authorization(JSONObject().put("v",2).put("role","child").put("room",room).put("scope",scope).put("capability",capability)
            .put("child_key",encoded(decoder.decode(child.publicKey))).put("ts_ms",now).put("nonce",nonce).put("signature",signature))
    }

    /** True only for a `ready` whose `features` array lists "child_proof"; an older relay omits the field. */
    fun supportsChildProof(ready:JSONObject):Boolean {
        val features=ready.opt("features") as? JSONArray ?: return false
        return ready.opt("type")=="ready" && (0 until features.length()).any{features.opt(it)=="child_proof"}
    }

    fun socketUrl(endpoint:String,room:String,allowLocal:Boolean=false):String {
        require(room.matches(Regex("[a-f0-9]{64}")))
        val uri=try{URI(endpoint)}catch(_:Exception){throw IllegalArgumentException("Invalid relay endpoint")}
        val host=uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing relay host")
        val local=host=="localhost" || host=="127.0.0.1"
        require(uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.rawPath in listOf("","/"))
        require(if(allowLocal && local)uri.scheme in listOf("http","https") else uri.scheme=="https" && (uri.port==-1 || uri.port==443))
        require(allowLocal && local || host.matches(Regex("[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}")) &&
            !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".invalid") && !host.endsWith(".test"))
        val authority=uri.rawAuthority
        return "${if(uri.scheme=="https")"wss" else "ws"}://$authority/v1/rooms/$room"
    }
}
