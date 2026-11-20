package app.family.igguard

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.*
import java.security.MessageDigest
import java.util.UUID

/** Child phone set-up shared by the tests of the held-capture and upload-method fixes. Invented data only. */
abstract class UploadTestBase {
    protected val ctx: Application get() = RuntimeEnvironment.getApplication()
    protected val prefs get() = LocalVault.preferences(ctx)
    protected val scope get() = GuardEngine.getChannelTopic(ctx)
    protected val token get() = GuardEngine.getOrCreateFamilyToken(ctx)
    protected val receipts get() = ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE)
    private var original: ProxySelector? = null
    private val tinyBytes = "tiny model file for tests".toByteArray()

    @Before fun resetChild() {
        TestKeyStore.failReads = false
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit(); receipts.edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        RiskScoringJob.scorerFactory = { error("no native scorer in unit tests") }
        ctx.getSystemService(JobScheduler::class.java).cancelAll()
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key", FamilySecurity.newSigningIdentity().publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        original = original ?: ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(u: URI) = if (u.host == "ntfy.sh") listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))) else original?.select(u) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(u: URI, a: SocketAddress, e: IOException) {}
        })
    }
    @After fun cleanupChild() {
        RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        ProxySelector.setDefault(original); original = null
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
    }

    protected fun installModel() {
        val sha = MessageDigest.getInstance("SHA-256").digest(tinyBytes).joinToString("") { "%02x".format(it) }
        RiskModelStore.expectation = RiskModelStore.Expectation(tinyBytes.size.toLong(), sha)
        assertEquals(RiskImportResult.OK, RiskModelStore.importModel(ctx, ByteArrayInputStream(tinyBytes), {}, tinyBytes.size.toLong(), sha))
        RiskLocal.changed(ctx)
    }
    /** The policy is on for [method], the child agreed to it, and analysis can run. */
    protected fun activate(method: String) {
        installModel(); RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true, method)
        assertTrue(RiskLocal.accept(ctx)); assertTrue(RiskLocal.active(ctx)); assertEquals(method, RiskLocal.agreedMethod(ctx))
    }
    protected fun switchOff() = RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), false)
    protected fun count(table: String) = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    protected fun outbox(): List<JSONObject> {
        val wires = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT wire FROM outbox WHERE scope=?", arrayOf(scope)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
        return wires.mapNotNull { GuardEngine.decryptPayload(token, it) }
    }
    protected fun heartbeats() = outbox().filter { it.optString("type") == "alert" && it.optString("reason") in setOf("heartbeat_ok", "tamper_attempt") }
}
