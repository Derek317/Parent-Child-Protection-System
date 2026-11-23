package app.family.igguard

import org.json.JSONObject

/**
 * "Records not delivered" warning. A QR-paired child puts three fixed-width fields inside every signed heartbeat: how many of
 * its records still wait for a parent receipt (heartbeats excluded), how old the oldest is, and whether one ever expired
 * unreceipted. The parent, on a VERIFIED heartbeat only, raises one content-free notice at most once an hour.
 * Counted: everything that waits for a parent receipt (alerts, tamper notices, pairing_verified, captured messages).
 * Not counted: scores reports and other control records (they do not wait for a receipt).
 */
object DeliveryHealth {
    // Child (encrypted preferences): ids of heartbeat events (not counted), and when a counted record last expired unreceipted.
    const val HEARTBEAT_IDS = "child_heartbeat_event_ids"
    const val EXPIRED_AT_MS = "child_receipt_expired_ms"
    // Parent: the notice's own once-per-hour timer, and when the current run of bad verified heartbeats began.
    const val NOTICE_MS = "child_undelivered_notice_ms"
    const val FIRST_BAD_MS = "child_undelivered_first_bad_ms"
    /** The child's send time (signed ts_ms) of the last heartbeat whose fields were acted on: an older or equal one is ignored. */
    const val LAST_SENT_MS = "child_undelivered_last_sent_ms"
    /** The parent's notice state: dropped with the pin (new pairing, rollback, remote reset) and with the family or the role. */
    /** Parent: the child's send time (sent_at_ms) of the newest scores report stored here. */
    const val REPORT_APPLIED_MS = "child_scores_applied_sent_ms"
    val PARENT_KEYS = listOf(NOTICE_MS, FIRST_BAD_MS, LAST_SENT_MS, REPORT_APPLIED_MS)

    const val NOTICE_SENDER = "送達檢查"
    const val NOTICE_TEXT = "有子女手機的回報超過 1 小時仍未送達此手機。請檢查兩部手機的網絡；如持續出現，請當面查看子女手機。 / Reports from the child phone have not reached this phone for over an hour. Check both phones' network; if it continues, check the child phone in person."
    const val NOTICE_INTERVAL_MS = 60 * 60_000L
    /** Receipts must have had a chance to flow: the parent has been continuously relay-connected at least this long. */
    const val READY_FOR_MS = 15 * 60_000L
    const val OLDEST_LIMIT_MIN = 60
    /** A bad run this long raises the notice even without 15 continuous minutes of connection here: a relay that keeps dropping
     *  this phone's socket cannot hold the notice back for ever. */
    const val LONG_BAD_MS = 3 * 60 * 60_000L

    /** The three heartbeat fields, always present and fixed width (2, 4 and 1 characters). */
    /** [reportSentAt]: send time of the newest scores report the child queued for this family (0: none), 13 digits. */
    fun fields(waiting: Int, oldestEnqueuedAt: Long?, expired: Boolean, now: Long, reportSentAt: Long = 0L): JSONObject {
        val minutes = if (oldestEnqueuedAt == null) 0L else ((now - oldestEnqueuedAt).coerceAtLeast(0L) / 60_000L)
        return JSONObject().put("waiting", "%02d".format(waiting.coerceIn(0, 99)))
            .put("oldest_min", "%04d".format(minutes.coerceIn(0L, 9999L)))
            .put("expired", if (expired) "1" else "0")
            .put("report_ms", "%013d".format(reportSentAt.coerceIn(0L, 9_999_999_999_999L)))
    }

    /** [reportMissing]: the heartbeat names a scores report sent over 60 minutes before it that the parent has not stored. */
    data class Report(val waiting: Int, val oldestMin: Int, val expired: Boolean, val reportMissing: Boolean = false) {
        val bad get() = expired || reportMissing || waiting > 0 && oldestMin >= OLDEST_LIMIT_MIN
        val clear get() = waiting == 0 && !expired && !reportMissing
    }
    private val two = Regex("[0-9]{2}"); private val four = Regex("[0-9]{4}"); private val one = Regex("[01]"); private val thirteen = Regex("[0-9]{13}")
    /** Strict: exactly 2 / 4 / 1 / 13 digits, else null (the fields are then ignored; the heartbeat itself still counts).
     *  [heartbeatSentAt] is the heartbeat's own signed ts_ms; [appliedReportSentAt] the newest report send time stored here. */
    fun parse(record: JSONObject, heartbeatSentAt: Long = 0L, appliedReportSentAt: Long = 0L): Report? {
        val w = record.opt("waiting") as? String ?: return null
        val o = record.opt("oldest_min") as? String ?: return null
        val e = record.opt("expired") as? String ?: return null
        val r = record.opt("report_ms") as? String ?: return null
        if (!two.matches(w) || !four.matches(o) || !one.matches(e) || !thirteen.matches(r)) return null
        val report = r.toLong()
        val missing = report > 0L && heartbeatSentAt - report > OLDEST_LIMIT_MIN * 60_000L && report > appliedReportSentAt
        return Report(w.toInt(), o.toInt(), e == "1", missing)
    }
}
