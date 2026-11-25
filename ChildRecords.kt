package app.family.igguard

import org.json.JSONObject
import java.util.Base64

/**
 * Signed child records (design m2-and-multichild, 3.2-3.5): the child-side wrapper and the parent's decision on each decrypted,
 * reassembled frame. Pure: no storage, no UI, no logging; the caller persists the control state and the tamper-notice time.
 * Not wired into any flow yet.
 */
object ChildRecords {
    /** Content-free local evidence row raised for [GateResult.Tamper], reason `tamper_attempt` (design 3.5). */
    const val TAMPER_SENDER = "訊息核對"
    const val TAMPER_TEXT = "收到一則未能核實來自此子女手機的訊息，已拒絕。內容未被保存。"
    const val TAMPER_INTERVAL_MS = 60 * 60_000L
    /** Control record ids are remembered this long past their record time, as parent commands are (MainActivity control_state_json). */
    const val CONTROL_KEEP_MS = 24 * 60 * 60_000L

    private val encoder = Base64.getUrlEncoder()
    private val contentTypes = setOf("alert", "captured_message")
    /** What may continue into the parent's `when (msgType)`. pair_hello verifies in FamilySecurity but belongs to the pairing room. */
    private val parentTypes = contentTypes + setOf("capture_ack", "sync_child_handle", "scores_mode_report")
    /** Replay and ordering kept here; alerts/captured messages (evidence store) and scores reports (risk_replay) are the caller's. */
    private val stateTypes = setOf("capture_ack", "sync_child_handle")

    /** Every (type, reason) a child phone sends to its parent today; reason null for control records. Checked against the sources by ChildRecordsTest. */
    val SENT_BY_CHILD: List<Pair<String, String?>> =
        listOf("heartbeat_ok", "pairing_verified", "test_alert", "tamper_attempt", "unlock_used",
            "stranger_message", "sexual_or_grooming_content", "flagged_contact_follow_up").map { "alert" to it } +
        listOf("conversation_context", "stranger_message", "sexual_or_grooming_content", "flagged_contact_follow_up").map { "captured_message" to it } +
        listOf("capture_ack" to null, "sync_child_handle" to null, "scores_mode_report" to null)

    enum class TamperKind { UNSIGNED, BAD_SIGNATURE, REWRAPPED }
    enum class DropReason { OUT_OF_WINDOW, UNKNOWN_TYPE, UNKNOWN_REASON, BAD_ID, TOO_LARGE, MALFORMED_RECORD, NO_PINNED_KEY }

    sealed class GateResult {
        /** [record] is what the existing branches read as `inner`. [newControlState] is the state to persist for this child, or
         * null when this type keeps no state here (keep the previous string). */
        data class Verified(val record: JSONObject, val type: String, val id: String, val timeMs: Long, val newControlState: String?) : GateResult()
        /** No pinned key and no child_signed: a pairing from before signed records. Process as today; show the child "not verified". */
        data class Legacy(val frame: JSONObject) : GateResult()
        /** Refuse; raise the content-free notice if [tamperNoticeDue]. Never a sign of life. */
        data class Tamper(val kind: TamperKind) : GateResult()
        /** Genuine signature, record outside the rules (or unverifiable without a pinned key): drop and count locally, no notice. */
        data class QuietDrop(val reason: DropReason) : GateResult()
        /** Verified, but its id was seen or its type's time went backwards: consume without effect, no notice. */
        object Replay : GateResult()
    }

    /**
     * Child side: the wrapper exactly as the transport sends it. [childKey] is K's public key text; [signer] signs with K
     * (production: `{ FamilySecurity.signChildBytes(it, key.alias) }`). P and K are hashed in canonical text form.
     * [receiptRequested] is the unsigned transport field the alert path puts on the outer object; null leaves it out (control records).
     */
    fun wrapForParent(recordText: String, eventIdOrControlId: String, parentPublicKey: String, childKey: String,
                      signer: (ByteArray) -> ByteArray, receiptRequested: Boolean? = null): JSONObject {
        val record = JSONObject(recordText)
        val expected = if (record.optString("type") in contentTypes) record.optString("event_id") else "control:" + record.optString("command_id")
        require(eventIdOrControlId == expected) { "Wrapper id must be the record's own id" }
        val p = requireNotNull(FamilySecurity.canonicalKeyText(parentPublicKey)) { "Invalid parent key text" }
        val k = requireNotNull(FamilySecurity.canonicalKeyText(childKey)) { "Invalid child key text" }
        val wrapper = JSONObject().put("type", "alert").put("event_id", eventIdOrControlId).put("child_signed", recordText)
            .put("child_sig", encoder.encodeToString(signer(FamilySecurity.childRecordSignedBytes(recordText, p, k))))
        if (receiptRequested != null) wrapper.put("receipt_requested", receiptRequested)
        return wrapper
    }

    /**
     * Parent side, for one decrypted and reassembled [frame] from one child. [pinnedChildKey] and [parentPublicKey] may be in
     * padded or unpadded base64url. [controlState] is this child's previous state string (null or unreadable: empty).
     * Throws IllegalStateException if a pinned key or the parent key is not a P-256 key text: a local fault, never the child's.
     */
    fun gate(frame: JSONObject, pinnedChildKey: String?, parentPublicKey: String, now: Long, controlState: String?): GateResult {
        if (pinnedChildKey == null) return if (frame.has("child_signed")) GateResult.QuietDrop(DropReason.NO_PINNED_KEY) else GateResult.Legacy(frame)
        // A pinned key never falls back to the unsigned 5.2 format: that would be a downgrade.
        if (frame.opt("child_signed") !is String || frame.opt("child_sig") !is String) return GateResult.Tamper(TamperKind.UNSIGNED)
        val child = when (val r = FamilySecurity.checkChildRecord(frame, pinnedChildKey, parentPublicKey, now)) {
            FamilySecurity.ChildRecordResult.InvalidKey -> throw IllegalStateException("Pinned child key or parent key is not a P-256 key")
            FamilySecurity.ChildRecordResult.BadSignature -> return GateResult.Tamper(TamperKind.BAD_SIGNATURE)
            // A genuine record under another wrapper id: only a holder of the family secret can re-wrap, never an honest child.
            is FamilySecurity.ChildRecordResult.Rejected -> return if (r.reason == FamilySecurity.ChildRecordRejection.ID_MISMATCH)
                GateResult.Tamper(TamperKind.REWRAPPED) else GateResult.QuietDrop(DropReason.valueOf(r.reason.name))
            is FamilySecurity.ChildRecordResult.Verified -> r.child
        }
        if (child.type !in parentTypes) return GateResult.QuietDrop(DropReason.UNKNOWN_TYPE)
        val record = child.record
        // As fragment reassembly does: the unsigned wrapper flag only decides whether a receipt is sent. Never added to control
        // records, whose strict codec requires an exact key set.
        if (child.type in contentTypes && frame.optBoolean("receipt_requested")) record.put("receipt_requested", true)
        if (child.type !in stateTypes) return GateResult.Verified(record, child.type, child.id, child.timeMs, null)
        val state = try { JSONObject(controlState ?: "{}") } catch (_: Exception) { JSONObject() }
        val ids = state.optJSONObject("ids") ?: JSONObject().also { state.put("ids", it) }
        if (ids.has(child.id) || child.timeMs < state.optLong(child.type)) return GateResult.Replay
        for (key in ids.keys().asSequence().toList()) if (ids.optLong(key) < now - CONTROL_KEEP_MS) ids.remove(key)
        // The ordering mark is clamped to now (a record may be dated 24 h ahead); the id keeps its own time so it stays a replay
        // for as long as the record itself is in its window.
        ids.put(child.id, child.timeMs); state.put(child.type, minOf(child.timeMs, now))
        return GateResult.Verified(record, child.type, child.id, child.timeMs, state.toString())
    }

    /** At most one tamper notice per child per hour. [lastNoticeAtMs] is 0 when none was raised; a clock that went back allows one. */
    fun tamperNoticeDue(lastNoticeAtMs: Long, now: Long): Boolean =
        lastNoticeAtMs <= 0L || now < lastNoticeAtMs || now - lastNoticeAtMs >= TAMPER_INTERVAL_MS
}
