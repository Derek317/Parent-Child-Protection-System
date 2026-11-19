package app.family.igguard

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B4: the child's clock (ts_ms) is for display only; retention runs from the parent's receipt time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class EvidenceClockTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val day=24*60*60_000L
    @Before fun reset(){LocalVault.preferences(ctx).edit().clear().commit();ctx.deleteDatabase("family_evidence.db");GuardEngine.setRole(ctx,"parent")}
    private fun alert(id:String,stamp:Long)=JSONObject().put("type","alert").put("event_id",id).put("sender","peer").put("text","body $id")
        .put("reason","stranger_message").put("ts_ms",stamp)
    @Test fun alertWithAChildClockSixtyDaysInThePastIsStoredShownAndKeptForTheFullPeriod() {
        val received=System.currentTimeMillis()
        assertTrue(GuardEngine.recordParentAlert(ctx,alert("old-clock",received-60*day),"relay:old-clock"))
        val shown=GuardEngine.getParentAlerts(ctx)
        assertEquals("stored and shown despite the old child timestamp",1,shown.size)
        assertEquals("the child's timestamp is kept for display",received-60*day,shown[0].optLong("ts_ms"))
        val scope=GuardEngine.getChannelTopic(ctx)
        FamilyEvidence(ctx).use{assertEquals(0,it.pruneExpired(scope,received+29*day))}
        assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
        FamilyEvidence(ctx).use{assertEquals(1,it.pruneExpired(scope,received+31*day))}
        assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
    }
    @Test fun aFutureChildTimestampDoesNotExtendRetention() {
        val received=System.currentTimeMillis()
        GuardEngine.recordParentAlert(ctx,alert("future",received+90*day),"relay:future")
        FamilyEvidence(ctx).use{assertEquals(1,it.pruneExpired(GuardEngine.getChannelTopic(ctx),received+31*day))}
    }
    @Test fun aResentAlertAfterAnEarlyPruneIsNotIgnoredWhenTheClockWasWrong() {
        val received=System.currentTimeMillis()
        GuardEngine.recordParentAlert(ctx,alert("tamper",received-90*day),"relay:tamper")
        assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
        assertFalse("a duplicate is still recognised",GuardEngine.recordParentAlert(ctx,alert("tamper",received-90*day),"relay:tamper2"))
    }
}
