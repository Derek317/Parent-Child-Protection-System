package app.family.igguard

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RelayOutboxTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()}

    @Test fun manualRetryCannotResetAcceptedFramesOrProviderCooldown() {
        FamilyEvidence(ctx).use { db ->
            db.enqueueBatch("f",listOf("accepted:0" to "cipher"),true);db.relayAccepted("f","accepted:0",1_000_000)
            db.enqueue("f","new:0","new cipher")
            db.retryNow("f")
            assertEquals(listOf("new:0"),db.due("f",1_000_001).map{it.id})
            db.failed("f","new:0",1_000_001)
            db.retryNow("f")
            assertTrue("Forced retry must preserve failure cooldown",db.due("f",1_000_002).isEmpty())
        }
    }

    @Test fun unsentPartsProgressBeforeAcceptedPrefixBecomesDueAgain() {
        FamilyEvidence(ctx).use { db ->
            db.enqueueBatch("f",(0..79).map{"long:$it" to "cipher $it"},true)
            for(n in 0..31)db.relayAccepted("f","long:$n",1000)
            assertTrue(db.due("f",100_000).all{it.id.substringAfterLast(':').toInt()>=32})
        }
    }

    @Test fun persistedParentControlCannotBeSentByTheChildRole() {
        FamilyEvidence(ctx).use { db ->
            db.enqueueBatch("f",listOf("command:0" to "cipher"),role="parent",kind="control")
            assertTrue(db.due("f",Long.MAX_VALUE).isEmpty())
            assertEquals("control",db.due("f",Long.MAX_VALUE,role="parent").single().kind)
        }
    }

    @Test fun rotatingEvidenceCannotMoveAnOldScopeSignedParentCommand() {
        FamilyEvidence(ctx).use { db ->
            db.enqueueBatch("old",listOf("command:0" to "signed old-scope cipher"),role="parent",kind="control")
            db.enqueue("old","event:0","child cipher")
            db.rotate("old","new"){"rekeyed child cipher"}
            assertEquals("signed old-scope cipher",db.due("old",Long.MAX_VALUE,"parent").single().wire)
            assertTrue(db.due("new",Long.MAX_VALUE,"parent").isEmpty())
            assertEquals("rekeyed child cipher",db.due("new",Long.MAX_VALUE).single().wire)
        }
    }
    @Test fun concurrentFirstOpenCannotRaceDatabaseCreation() {
        val start=java.util.concurrent.CountDownLatch(1)
        val workers=java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            val tasks=(0 until 12).map{index->workers.submit<Boolean>{start.await();FamilyEvidence(ctx).use{it.enqueue("f","event-$index:0","cipher");true}}}
            start.countDown()
            for(task in tasks)assertTrue(task.get(10,java.util.concurrent.TimeUnit.SECONDS))
            FamilyEvidence(ctx).use{assertEquals(12,it.pendingCount("f"))}
        }finally{workers.shutdownNow()}
    }
    @Test fun providerMigrationRequeuesOldCiphertextOnceWithoutResettingSubsequentReceiptWaits() {
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey).commit()
        val token=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx);val now=System.currentTimeMillis()
        val original=org.json.JSONObject().put("type","alert").put("event_id","old").put("text","old queued record").put("receipt_requested",false)
        FamilyEvidence(ctx).use { db ->
            db.enqueue(scope,"old:0",GuardEngine.encryptPayload(token,original.toString()))
            db.relayAccepted(scope,"old:0",now+3_600_000)
            db.writableDatabase.execSQL("UPDATE outbox SET relay_version=0 WHERE scope=?",arrayOf(scope))
        }
        GuardEngine.prepareRelayOutbox(ctx)
        FamilyEvidence(ctx).use { db ->
            val next=db.due(scope,now+1).single();assertTrue(next.waitForReceipt)
            val decoded=GuardEngine.decryptPayload(token,next.wire)!!
            assertEquals("old queued record",decoded.getString("text"));assertTrue(decoded.getBoolean("receipt_requested"))
            db.relayAccepted(scope,next.id,now+1000)
        }
        GuardEngine.prepareRelayOutbox(ctx)
        FamilyEvidence(ctx).use{assertTrue(it.due(scope,now+1001).isEmpty())}
    }
    @Test fun anUnreadableMigratedFrameCompletesTheCallerWithoutDiscardingEitherRecord() {
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey).commit()
        val scope=GuardEngine.getChannelTopic(ctx)
        FamilyEvidence(ctx).use{db->db.enqueue(scope,"old:0","unreadable ciphertext");db.writableDatabase.execSQL("UPDATE outbox SET relay_version=0 WHERE scope=?",arrayOf(scope))}
        val done=java.util.concurrent.CountDownLatch(1);var accepted=true
        GuardEngine.sendAlertFromChild(ctx,"test_alert","test","new record",onComplete={success,_->accepted=success;done.countDown()})
        assertTrue(done.await(2,java.util.concurrent.TimeUnit.SECONDS));assertFalse(accepted)
        FamilyEvidence(ctx).use{assertEquals(2,it.pendingCount(scope))}
    }
    @Test fun contextBackpressureCannotDeferNewUrgentEvidence() {
        FamilyEvidence(ctx).use{db->
            db.enqueue("f","context:0","cipher",kind="data")
            db.failed("f","context:0",1000,kindOnly="data")
            db.enqueue("f","urgent:0","cipher",kind="urgent")
            assertEquals(listOf("urgent:0"),db.due("f",1001).map{it.id})
        }
    }
}
