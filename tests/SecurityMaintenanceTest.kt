package app.family.igguard

import android.app.Application
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SecurityMaintenanceTest {
    @Implements(JobService::class)
    class FinishShadow : ShadowJobService() {
        @Implementation override fun jobFinished(params: JobParameters, needsReschedule: Boolean) { finished.add(params.jobId); super.jobFinished(params, needsReschedule) }
        companion object { val finished: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList()) }
    }
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val day=86_400_000L
    private fun event(id:String,now:Long,text:String="retained private content")=JSONObject()
        .put("type","alert").put("event_id",id).put("ts_ms",now)
        .put("reason","stranger_message").put("sender","person").put("text",text)

    @Before fun reset() {
        TestKeyStore.failReads=false
        ctx.deleteDatabase("family_evidence.db")
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
    }
    @After fun restoreKeys(){TestKeyStore.failReads=false}

    @Test fun schedulingUsesOnePersistedDailyJobWithoutNetworkOrChargingRequirements() {
        ArchiveMaintenance.schedule(ctx);ArchiveMaintenance.schedule(ctx)
        val jobs=ctx.getSystemService(JobScheduler::class.java).allPendingJobs
        assertEquals(1,jobs.size)
        val job=jobs.single()
        assertEquals("app.family.igguard.ArchiveMaintenanceJob",job.service.className)
        assertTrue(job.isPersisted);assertTrue(job.isPeriodic)
        assertEquals(day,job.intervalMillis)
        assertFalse(job.isRequireCharging);assertFalse(job.isRequireDeviceIdle)
        assertNull(job.requiredNetwork)
    }

    @Test fun pruningFindsEveryContentScopeAndPreservesLegacyActiveAndExplicitlyHeldArchives() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("archive-only","expired",event("expired",now))
            db.append("active","active",event("active",now))
            db.append("legacy","legacy",event("legacy",now))
            db.append("preserved","held",event("held",now));db.preserveForReview("preserved","held",now)
            db.writableDatabase.execSQL("UPDATE evidence SET expires=? WHERE id='expired'",arrayOf(now))
            db.writableDatabase.execSQL("UPDATE evidence SET retention_base=NULL,expires=NULL WHERE id='legacy'")
            db.enqueue("outbox-only","queued:0","ciphertext")
            db.writableDatabase.execSQL("UPDATE outbox_lifetimes SET expires=?",arrayOf(now))
            db.fragment("fragments-only",AlertFrames.split(event("partial",now,"secret".repeat(1000))).first())
            db.writableDatabase.execSQL("UPDATE fragments SET expires=?",arrayOf(now))
            db.setCaptureWindow("windows-only","thread",listOf("secret"))
            db.writableDatabase.execSQL("UPDATE capture_windows SET updated=?",arrayOf(now-3*day))
        }
        assertEquals(4,ArchiveMaintenance.pruneAll(ctx,now))
        FamilyEvidence(ctx).use { db ->
            assertNull(db.find("archive-only","expired"));assertTrue(db.hasSeen("archive-only","expired"))
            assertFalse(db.append("archive-only","expired",event("expired",now)))
            assertEquals(0,db.pendingCount("outbox-only"));db.enqueue("outbox-only","queued:1","replay")
            assertEquals(0,db.pendingCount("outbox-only"))
            assertEquals(0,db.incompleteCount("fragments-only"));assertTrue(db.hasSeen("fragments-only","partial"))
            assertTrue(db.captureWindow("windows-only","thread").isEmpty())
            for((scope,id) in listOf("active" to "active","legacy" to "legacy","preserved" to "held"))
                assertEquals("retained private content",db.find(scope,id)!!.getString("text"))
        }
        assertEquals(0,ArchiveMaintenance.pruneAll(ctx,now))
    }

    private fun expiredArchive(now:Long) {
        FamilyEvidence(ctx).use { db ->
            db.append("family","expired",event("expired",now))
            db.writableDatabase.execSQL("UPDATE evidence SET expires=?",arrayOf(now))
        }
    }

    @Test fun unavailableKeysRetainDataAndKeysAndAllowALaterRetry() {
        val now=System.currentTimeMillis();expiredArchive(now)
        val keys=TestKeyStore.keys.toMap()
        TestKeyStore.failReads=true
        try {ArchiveMaintenance.pruneAll(ctx,now);fail("Unreadable keys must stop maintenance")}
        catch(_:SecurityException){}
        finally {TestKeyStore.failReads=false}
        assertEquals(keys,TestKeyStore.keys.toMap())
        FamilyEvidence(ctx).use {assertEquals("retained private content",it.find("family","expired")!!.getString("text"))}
        assertEquals(1,ArchiveMaintenance.pruneAll(ctx,now))
    }

    @Test fun interruptionStopsPruningWithoutDeletingData() {
        val now=System.currentTimeMillis();expiredArchive(now)
        try {
            Thread.currentThread().interrupt()
            try {ArchiveMaintenance.pruneAll(ctx,now);fail("Stopped jobs must stop pruning")}
            catch(_:InterruptedException){}
        }finally{Thread.interrupted()}
        FamilyEvidence(ctx).use {assertNotNull(it.find("family","expired"))}
    }

    private fun awaitFinished(service:JobService):ShadowJobService {
        val state=Shadow.extract<ShadowJobService>(service)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!state.isJobFinished) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Maintenance did not finish",System.nanoTime()<deadline)
            Thread.sleep(10)
        }
        return state
    }

    @Test fun jobPrunesOffTheMainThreadAndFinishesWithoutRequestingRetry() {
        val now=System.currentTimeMillis();expiredArchive(now)
        val controller=Robolectric.buildService(ArchiveMaintenanceJob::class.java).create()
        try {
            assertTrue(controller.get().onStartJob(Shadow.newInstanceOf(JobParameters::class.java)))
            assertFalse(awaitFinished(controller.get()).isRescheduleNeeded)
            FamilyEvidence(ctx).use {assertNull(it.find("family","expired"))}
        }finally{controller.destroy()}
    }

    @Test @Config(shadows=[FinishShadow::class]) fun thePruneAndHeartbeatJobsRunningTogetherEachFinish() {
        FinishShadow.finished.clear()
        val controller=Robolectric.buildService(ArchiveMaintenanceJob::class.java).create()
        try {
            fun params(id:Int)=Shadow.newInstanceOf(JobParameters::class.java).also{ReflectionHelpers.setField(it,"jobId",id)}
            assertTrue(controller.get().onStartJob(params(ArchiveMaintenance.HEARTBEAT_ID-1)))
            assertTrue(controller.get().onStartJob(params(ArchiveMaintenance.HEARTBEAT_ID)))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(FinishShadow.finished.size<2 && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            assertEquals(listOf(ArchiveMaintenance.HEARTBEAT_ID-1,ArchiveMaintenance.HEARTBEAT_ID),FinishShadow.finished.sorted())
        }finally{controller.destroy()}
    }

    @Test fun jobRequestsRetryWhenKeysAreUnavailableAndOnSystemStop() {
        val now=System.currentTimeMillis();expiredArchive(now)
        val controller=Robolectric.buildService(ArchiveMaintenanceJob::class.java).create()
        try {
            TestKeyStore.failReads=true
            val params=Shadow.newInstanceOf(JobParameters::class.java)
            assertTrue(controller.get().onStartJob(params))
            assertTrue(awaitFinished(controller.get()).isRescheduleNeeded)
            assertTrue(controller.get().onStopJob(params))
        }finally{TestKeyStore.failReads=false;controller.destroy()}
        FamilyEvidence(ctx).use {assertNotNull(it.find("family","expired"))}
    }
}
