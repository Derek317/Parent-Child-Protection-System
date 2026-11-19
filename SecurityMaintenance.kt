package app.family.igguard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Android may defer background work; archive readers must enforce expiry independently. */
object ArchiveMaintenance {
    private const val JOB_ID=0x49474752

    fun schedule(ctx:Context) {
        val app=ctx.applicationContext
        val scheduler=app.getSystemService(JobScheduler::class.java) ?: return
        // Frequent component/UI refreshes must not postpone an already scheduled cleanup.
        if(scheduler.getPendingJob(JOB_ID)!=null)return
        val job=JobInfo.Builder(JOB_ID,ComponentName(app,ArchiveMaintenanceJob::class.java))
            .setPeriodic(86_400_000L).setPersisted(true).build()
        if(scheduler.schedule(job)!=JobScheduler.RESULT_SUCCESS)
            Log.w("IGGuard","Android refused archive maintenance scheduling")
    }

    fun pruneAll(ctx:Context,now:Long=System.currentTimeMillis()):Int {
        if(Thread.currentThread().isInterrupted)throw InterruptedException()
        // FamilyEvidence requires the archive keys on open; failures propagate without resetting them.
        val pruned = FamilyEvidence(ctx).use { archive ->
            val scopes=archive.readableDatabase.rawQuery(
                "SELECT scope FROM evidence UNION SELECT scope FROM outbox UNION SELECT scope FROM fragments UNION SELECT scope FROM capture_windows UNION SELECT scope FROM risk_store UNION SELECT scope FROM risk_threads UNION SELECT scope FROM risk_results UNION SELECT scope FROM risk_unknown UNION SELECT scope FROM risk_scores UNION SELECT scope FROM risk_state UNION SELECT scope FROM risk_replay UNION SELECT scope FROM risk_tombstones",null
            ).use { cursor -> buildList {while(cursor.moveToNext())add(cursor.getString(0))} }
            scopes.sumOf { scope ->
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                archive.pruneExpired(scope,now)
            }
        }
        // The scoring store exists only while scores-only mode is accepted and on; anything else is wiped here.
        RiskLocal.maintain(ctx)
        return pruned
    }
}

class ArchiveMaintenanceJob : JobService() {
    private var worker:Thread?=null
    private val completion=Handler(Looper.getMainLooper())

    override fun onStartJob(params:JobParameters):Boolean {
        worker=Thread({
            val retry=try {ArchiveMaintenance.pruneAll(applicationContext);false}
                catch(_:Exception){true}
            val current=Thread.currentThread()
            completion.post {
                if(worker===current) {
                    worker=null
                    jobFinished(params,retry)
                }
            }
        },"Archive maintenance").apply{start()}
        return true
    }

    override fun onStopJob(params:JobParameters):Boolean {
        worker?.interrupt();worker=null
        return true
    }

    override fun onDestroy() {
        worker?.interrupt();worker=null
        super.onDestroy()
    }
}
