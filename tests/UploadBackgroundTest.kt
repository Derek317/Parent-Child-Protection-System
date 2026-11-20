package app.family.igguard

import android.app.job.JobParameters
import android.app.job.JobScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** With Notification access and Accessibility both off (allowed under manual upload) the phone must still report and poll the relay on its own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UploadBackgroundTest : UploadTestBase() {
    private fun due() { prefs.edit().putLong("next_heartbeat_at_ms", 1L).commit() }
    private fun reasons() = heartbeats().map { it.optString("reason") }

    @Test fun theScreenServiceStillReportsWhenDueUnderUploadButReadsNothing() {
        for (held in listOf(false, true)) {
            resetChild(); activate(ScoresMethod.UPLOAD); if (held) { switchOff(); assertTrue(RiskLocal.captureHeld(ctx)) }
            due(); GuardEngine.cpAccConnected = false
            val service = Robolectric.buildService(IgAccessibilityService::class.java).create()
            try {
                service.get().onAccessibilityEvent(android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = GuardEngine.INSTAGRAM_PKG })
                assertFalse("held=$held: the screen is not read", GuardEngine.cpAccConnected)
            } finally { service.destroy() }
            assertEquals("held=$held", 1, heartbeats().size)
        }
    }
    @Test fun aPeriodicJobIsScheduledForAChildPhoneAndNotForOtherRoles() {
        ArchiveMaintenance.schedule(ctx)
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        val job = scheduler.getPendingJob(ArchiveMaintenance.HEARTBEAT_ID)!!
        assertEquals("app.family.igguard.ArchiveMaintenanceJob", job.service.className)
        assertTrue(job.isPeriodic); assertTrue(job.isPersisted); assertFalse(job.isRequireCharging); assertFalse(job.isRequireDeviceIdle)
        assertTrue("the daily cleanup is still there", scheduler.allPendingJobs.size >= 2)
        GuardEngine.setRole(ctx, "parent"); ArchiveMaintenance.schedule(ctx)
        assertNull(scheduler.getPendingJob(ArchiveMaintenance.HEARTBEAT_ID))
    }
    @Test fun thatJobSendsTheDueHeartbeatWithBothAccessesOffUnderUpload() {
        activate(ScoresMethod.UPLOAD); assertFalse(GuardEngine.isNotificationListenerEnabledStatic(ctx)); assertFalse(GuardEngine.isAccessibilityEnabledStatic(ctx))
        due()
        val controller = Robolectric.buildService(ArchiveMaintenanceJob::class.java).create()
        try {
            val params = Shadow.newInstanceOf(JobParameters::class.java); ReflectionHelpers.setField(params, "jobId", ArchiveMaintenance.HEARTBEAT_ID)
            assertTrue(controller.get().onStartJob(params))
            val deadline = System.nanoTime() + 10_000_000_000L
            while (heartbeats().isEmpty() && System.nanoTime() < deadline) Thread.sleep(25)
        } finally { controller.destroy() }
        assertEquals(listOf("heartbeat_ok"), reasons())
    }
    @Test fun theDailyCleanupJobDoesNotSendAHeartbeat() {
        activate(ScoresMethod.UPLOAD); due()
        val controller = Robolectric.buildService(ArchiveMaintenanceJob::class.java).create()
        try {
            assertTrue(controller.get().onStartJob(Shadow.newInstanceOf(JobParameters::class.java)))
            Thread.sleep(500)
        } finally { controller.destroy() }
        assertTrue(reasons().isEmpty())
    }
}
