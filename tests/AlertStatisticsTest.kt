package app.family.igguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlertStatisticsTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
    private fun event(reason: String = "stranger_message", ts: Long = now) = JSONObject()
        .put("reason", reason).put("ts_ms", ts).put("sender", "test").put("text", "example")
    private fun stats() = AlertStatistics(JSONObject().put("zone", "Asia/Hong_Kong"), now)

    @Test fun sevenDaysZeroFilledAndTypesAgree() {
        val s = stats()
        s.record("a", event())
        s.record("b", event("sexual_or_grooming_content"))
        assertEquals(7, s.dailyTotals().size)
        assertEquals(listOf(0L,0L,0L,0L,0L,0L,2L), s.dailyTotals())
        assertEquals(s.dailyTotals().sum(), s.typeTotals().sum())
    }
    @Test fun calendarBoundaryAndOldEvents() {
        val s = stats()
        s.record("old", event(ts=Instant.parse("2026-10-27T15:59:59Z").toEpochMilli()))
        s.record("first", event(ts=Instant.parse("2026-10-27T16:00:00Z").toEpochMilli()))
        assertEquals(1L, s.dailyTotals().first())
        assertEquals(1L, s.dailyTotals().sum())
    }
    @Test fun excludesRoutineEvents() {
        val s=stats()
        listOf("heartbeat_ok","pairing_verified","test_alert").forEach { s.record(it,event(it)) }
        assertEquals(0L,s.dailyTotals().sum())
        s.record("offline",event("tamper_attempt"))
        s.record("unlock",event("unlock_used"))
        assertEquals(2L,s.dailyTotals().sum())
    }
    @Test fun replayAndResetKeepDedupe() {
        val s=stats()
        s.record("a",event());s.record("a",event())
        assertEquals(1L,s.dailyTotals().sum())
        s.reset();s.record("a",event())
        assertEquals(0L,s.dailyTotals().sum())
        s.record("b",event());assertEquals(1L,s.dailyTotals().sum())
    }
    @Test fun migrationDoesNotDoubleCountRelayReplay() {
        val s=stats()
        s.migrate(listOf(event()))
        s.record("relay-id",event())
        assertEquals(1L,s.dailyTotals().sum())
        s.record("different-event",event(ts=now-1))
        assertEquals(2L,s.dailyTotals().sum())
    }
    @Test fun invalidTimestampFallsBackAndPersistsZone() {
        val s=stats()
        s.record("missing",event(ts=0));s.record("future",event(ts=Long.MAX_VALUE))
        val restored=AlertStatistics(JSONObject(s.state.toString()),now)
        assertEquals("Asia/Hong_Kong",restored.zone.id)
        assertEquals(2L,restored.dailyTotals().last())
    }
    @Test fun retentionPrunesCountsAndIds() {
        val s=stats();s.record("a",event())
        val later=AlertStatistics(JSONObject(s.state.toString()),now+9*86400000L)
        assertEquals(0L,later.dailyTotals().sum())
        assertEquals(0,later.state.getJSONObject("seen").length())
    }
    @Test fun dstUsesCalendarDays() {
        val s=AlertStatistics(JSONObject().put("zone","America/New_York"),now)
        s.record("before",event(ts=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()))
        s.record("after",event(ts=Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()))
        assertEquals(2L,s.dailyTotals()[4])
    }
}
