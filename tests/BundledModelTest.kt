package app.family.igguard

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.Button
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
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** The model included in the app: automatic install on a child phone, with the same checks as the manual import. The asset is faked by a tiny file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h700dp-mdpi")
class BundledModelTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    private val tiny = "tiny model file for tests".toByteArray()
    private val parentVerifier by lazy { FamilySecurity.passwordVerifier("Parent12345") }
    private var opened = 0
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun part() = File(RiskModelStore.directory(ctx), RiskModelSpec.FILE_NAME + ".part")
    private fun asset(bytes: ByteArray = tiny) { RiskModelStore.bundledOpener = { opened++; ByteArrayInputStream(bytes) } }

    @Before fun reset() {
        ctx.deleteDatabase("family_evidence.db"); prefs.edit().clear().commit()
        ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively(); opened = 0
        RiskModelStore.expectation = RiskModelStore.Expectation(tiny.size.toLong(), sha(tiny))
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        RiskModelStore.bundledOpener = { null }; RiskModelStore.freeSpaceProbe = { it.usableSpace }
        RiskScoringJob.scorerFactory = { error("no native scorer in unit tests") }
        ModelImportJob.runner = { it.run() }; ModelImportJob.dismissResult(); ModelImportJob.running = false; ModelImportJob.bundled = false
        ModelImportJob.importer = { c, i, p -> RiskLocal.importModel(c, i, p) }
        GuardEngine.setFamilyToken(ctx, UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
    }
    @After fun cleanup() {
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        RiskModelStore.bundledOpener = { c -> try { c.applicationContext.assets.open(RiskModelStore.bundledAssetPath()) } catch (_: IOException) { null } }
        RiskModelStore.freeSpaceProbe = { it.usableSpace }
        RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        RiskModelStore.memoryProbe = { c -> val i = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(i); i.totalMem }
        ModelImportJob.runner = { Thread(it, "Model import").start() }; ModelImportJob.dismissResult(); ModelImportJob.running = false; ModelImportJob.bundled = false
    }
    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().also { UiSupport.nav(it.get(), "permissions") }
    private fun tag(c: org.robolectric.android.controller.ActivityController<MainActivity>, t: String) = UiSupport.all(c.get().window.decorView).filter { it.tag == t }
    private fun answerPassword(password: String) {
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        UiSupport.all(root).filterIsInstance<android.widget.EditText>().single { it.tag == "role_password" }.setText(password)
        UiSupport.all(root).filterIsInstance<Button>().single { it.text.toString() == "驗證並繼續" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun childWithMissingModelAndAnIncludedCopyInstallsItWithoutAPassword() {
        asset()
        assertEquals(RiskModelState.MISSING, RiskModelStore.state(ctx)); assertTrue(RiskModelStore.bundledModelPresent(ctx))
        assertTrue(BundledModel.maybeAutoInstall(ctx))
        assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx)); assertTrue(RiskModelStore.verify(ctx))
        assertEquals(RiskImportResult.OK, ModelImportJob.result); assertTrue(ModelImportJob.bundled); assertFalse(part().exists())
        assertEquals("valid", RiskLocal.status(ctx).modelState)
        assertFalse("nothing to do once installed", BundledModel.maybeAutoInstall(ctx))
    }
    @Test fun settingTheChildRoleStartsTheInstall() {
        GuardEngine.setRole(ctx, "parent"); asset()
        GuardEngine.setRole(ctx, "child")
        assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
    }
    @Test fun startingTheAppOnAChildPhoneStartsTheInstall() {
        asset()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx)) } finally { c.pause().stop().destroy() }
    }
    @Test fun noInstallOnAParentPhone() {
        GuardEngine.setRole(ctx, "parent"); asset()
        assertFalse(BundledModel.maybeAutoInstall(ctx)); assertFalse(ModelImportJob.startBundled(ctx))
        assertFalse(RiskModelStore.file(ctx).exists())
    }
    @Test fun noInstallOnAPhoneThatCannotRunTheModel() {
        asset(); RiskModelStore.memoryProbe = { 2L shl 30 }
        assertFalse(BundledModel.maybeAutoInstall(ctx)); assertFalse(RiskModelStore.file(ctx).exists())
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { false }
        assertFalse(BundledModel.maybeAutoInstall(ctx)); assertFalse(RiskModelStore.file(ctx).exists())
    }
    @Test fun noInstallWhenTheAppHasNoIncludedCopy() {
        assertFalse(RiskModelStore.bundledModelPresent(ctx)); assertFalse(BundledModel.maybeAutoInstall(ctx))
        assertEquals(RiskModelState.MISSING, RiskModelStore.state(ctx))
    }
    @Test fun lowFreeSpaceStopsBeforeOpeningTheAssetAndSaysHowMuchIsNeeded() {
        asset(); RiskModelStore.freeSpaceProbe = { 1_000L }
        assertTrue(BundledModel.maybeAutoInstall(ctx))
        assertEquals(RiskImportResult.NOT_ENOUGH_SPACE, ModelImportJob.result)
        assertEquals("the asset was only probed for, never read", 1, opened)
        assertFalse(RiskModelStore.file(ctx).exists()); assertFalse(part().exists())
        val (title, body) = ScoresChildCopy.result(ModelImportJob.result, false, true, 10L * 1024 * 1024)!!
        assertTrue(title.contains("Not enough free space")); assertTrue(body.contains("0.0 GB") && body.contains("free space"))
        assertTrue(body.contains("%.1f GB".format(RiskModelStore.spaceNeeded() / 1073741824.0)))
        // not a permanent failure: the next start tries again, and succeeds once there is room
        RiskModelStore.freeSpaceProbe = { it.usableSpace }; ModelImportJob.dismissResult()
        assertTrue(BundledModel.maybeAutoInstall(ctx)); assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
    }
    @Test fun aWrongOrTruncatedIncludedCopyFailsClosedAndIsNotRetriedOnItsOwn() {
        for (bad in listOf(tiny.copyOf().also { it[0] = (it[0] + 1).toByte() }, tiny.copyOf(5), tiny + 1)) {
            ModelImportJob.dismissResult(); prefs.edit().remove("risk_bundled_auto_blocked").commit(); asset(bad)
            assertTrue(BundledModel.maybeAutoInstall(ctx))
            assertEquals(RiskImportResult.WRONG_FILE, ModelImportJob.result)
            assertFalse(RiskModelStore.file(ctx).exists()); assertFalse(part().exists()); assertNotEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
            assertFalse("a bad copy is not copied again at every start", BundledModel.maybeAutoInstall(ctx))
            assertTrue(ScoresChildCopy.result(RiskImportResult.WRONG_FILE, false, true)!!.first.contains("could not be installed"))
        }
        // a later, correct package (new install key) or a manual retry works
        asset(); ModelImportJob.dismissResult()
        assertTrue(ModelImportJob.startBundled(ctx)); assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
    }
    @Test fun anInterruptedInstallLeavesNoPartialFileAndTriesAgainFromScratch() {
        RiskModelStore.bundledOpener = { object : InputStream() { var n = 0; override fun read(): Int = if (n++ < 5) 1 else throw IOException("disk gone") } }
        assertTrue(BundledModel.maybeAutoInstall(ctx))
        assertEquals(RiskImportResult.IO_ERROR, ModelImportJob.result)
        assertFalse(RiskModelStore.file(ctx).exists()); assertFalse(part().exists())
        // a leftover from a process that was killed is removed by the next attempt
        RiskModelStore.directory(ctx).mkdirs(); part().writeBytes(ByteArray(100))
        asset(); ModelImportJob.dismissResult()
        assertTrue(BundledModel.maybeAutoInstall(ctx))
        assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx)); assertFalse(part().exists())
    }
    @Test fun cancellingKeepsNothingAndStopsAutomaticRetries() {
        asset()
        ModelImportJob.bundledInstaller = { c, src, p -> ModelImportJob.requestCancel(); RiskLocal.installBundledModel(c, src, p) }
        try {
            assertTrue(BundledModel.maybeAutoInstall(ctx))
            assertTrue(ModelImportJob.wasCancelled); assertFalse(RiskModelStore.file(ctx).exists()); assertFalse(part().exists())
            assertFalse(BundledModel.maybeAutoInstall(ctx))
        } finally { ModelImportJob.bundledInstaller = { c, s, p -> RiskLocal.installBundledModel(c, s, p) } }
    }
    @Test fun installingChangesNeitherConsentNorModeState() {
        RiskLocal.receivePolicy(ctx, UUID.randomUUID().toString(), true)
        fun snapshot() = listOf(RiskLocal.consentState(ctx), RiskLocal.policyEnabled(ctx), RiskLocal.policyId(ctx), RiskLocal.paused(ctx), RiskLocal.captureMode(ctx), RiskLocal.active(ctx), RiskLocal.pendingPolicy(ctx))
        val before = snapshot(); asset()
        assertTrue(BundledModel.maybeAutoInstall(ctx)); assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
        assertEquals(before, snapshot())
        assertEquals("none", RiskLocal.consentState(ctx)); assertFalse(RiskLocal.active(ctx))
        FamilyEvidence(ctx).use { db -> assertEquals(0, db.readableDatabase.rawQuery("SELECT COUNT(*) FROM risk_store", null).use { it.moveToFirst(); it.getInt(0) }) }
    }
    @Test fun afterARemovalTheCopyIsNotInstalledAgainUntilAParentAsksWithThePassword() {
        prefs.edit().putString("parent_password_verifier", parentVerifier).commit(); asset()
        assertTrue(BundledModel.maybeAutoInstall(ctx)); assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
        RiskLocal.removeModel(ctx)
        assertEquals(RiskModelState.MISSING, RiskModelStore.state(ctx)); assertTrue(BundledModel.needsPassword(ctx))
        assertFalse(BundledModel.maybeAutoInstall(ctx)); assertFalse(RiskModelStore.file(ctx).exists())
        val c = launch()   // starting the app does not bring it back either
        try {
            assertFalse(RiskModelStore.file(ctx).exists())
            tag(c, "model_bundled_row").single().performClick()
            assertTrue(UiSupport.texts(ShadowDialog.getLatestDialog().window!!.decorView).any { it.contains("parent's password") })
            answerPassword("wrong password"); assertFalse(RiskModelStore.file(ctx).exists())
            ShadowDialog.getLatestDialog().cancel(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(RiskModelStore.file(ctx).exists())
            tag(c, "model_bundled_row").single().performClick(); answerPassword("Parent12345")
            assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx)); assertFalse(BundledModel.needsPassword(ctx))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun aManualImportAlsoEndsTheRemovedState() {
        asset(); assertTrue(BundledModel.maybeAutoInstall(ctx)); RiskLocal.removeModel(ctx); assertTrue(BundledModel.needsPassword(ctx))
        assertEquals(RiskImportResult.OK, RiskModelStore.importModel(ctx, ByteArrayInputStream(tiny), {}, tiny.size.toLong(), sha(tiny)))
        assertFalse(BundledModel.needsPassword(ctx))
    }
    @Test fun withoutAnAssetTheManualPickerStillWorks() {
        val c = launch()
        try {
            assertTrue(tag(c, "model_bundled_row").isEmpty()); assertEquals(1, tag(c, "model_import_row").size)
            assertFalse(UiSupport.texts(c.get().window.decorView).any { it.contains("already includes the model") })
            val uri = android.net.Uri.parse("content://test.picker/model")
            shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(tiny))
            ModelImportJob.importer = { cx, i, p -> RiskModelStore.importModel(cx, i, p, tiny.size.toLong(), sha(tiny)) }
            assertTrue(ModelImportJob.start(ctx, uri))
            assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx)); assertFalse(ModelImportJob.bundled)
        } finally { ModelImportJob.importer = { cx, i, p -> RiskLocal.importModel(cx, i, p) }; c.pause().stop().destroy() }
    }
    @Test fun withAnAssetTheScreenOffersBothAndFailureShowsRetry() {
        prefs.edit().putString("parent_password_verifier", parentVerifier).commit()
        asset(tiny.copyOf(5)); RiskModelStore.blockAuto(ctx)   // the automatic attempt is not what is under test here
        val c = launch()
        try {
            val texts = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            assertEquals(1, tag(c, "model_bundled_row").size); assertEquals(1, tag(c, "model_import_row").size)
            assertTrue(texts.contains("Install the model included in this app") && texts.contains("安裝 App 內附的模型") && texts.contains("already includes the model"))
            tag(c, "model_bundled_row").single().performClick()   // no password: it was not removed
            assertNull(shadowOf(c.get()).nextStartedActivityForResult)
            val result = UiSupport.texts(tag(c, "scores_import_result").single()).joinToString("\n")
            assertTrue(result.contains("安裝失敗") && result.contains("could not be installed"))
            asset(); tag(c, "scores_bundled_retry").single().performClick()
            assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun progressIsWordedForTheIncludedCopy() {
        asset(); val c = launch()
        try {
            ModelImportJob.running = true; ModelImportJob.bundled = true; ModelImportJob.copied = 100L
            UiSupport.nav(c.get(), "overview"); UiSupport.nav(c.get(), "permissions")
            val texts = UiSupport.texts(c.get().window.decorView).joinToString("\n")
            assertTrue(texts.contains("Installing the model included in this app") && texts.contains("取消安裝"))
            assertEquals(1, tag(c, "scores_import_cancel").size)
        } finally { ModelImportJob.running = false; c.pause().stop().destroy() }
    }

    @Test fun retryAfterARemovalAlsoAsksForThePassword() {
        prefs.edit().putString("parent_password_verifier", parentVerifier).commit(); asset()
        assertTrue(BundledModel.maybeAutoInstall(ctx)); RiskLocal.removeModel(ctx); assertTrue(BundledModel.needsPassword(ctx))
        RiskModelStore.freeSpaceProbe = { 1_000L }   // the password-authorised reinstall runs out of space
        val c = launch()
        try {
            tag(c, "model_bundled_row").single().performClick(); answerPassword("Parent12345")
            assertEquals(RiskImportResult.NOT_ENOUGH_SPACE, ModelImportJob.result); assertTrue(BundledModel.needsPassword(ctx))
            RiskModelStore.freeSpaceProbe = { it.usableSpace }
            UiSupport.nav(c.get(), "overview"); UiSupport.nav(c.get(), "permissions")
            tag(c, "scores_bundled_retry").single().performClick()
            assertFalse("Retry must not install before the password", RiskModelStore.file(ctx).exists())
            assertTrue(UiSupport.texts(ShadowDialog.getLatestDialog().window!!.decorView).any { it.contains("parent's password") })
            answerPassword("wrong password"); assertFalse(RiskModelStore.file(ctx).exists())
            ShadowDialog.getLatestDialog().cancel(); shadowOf(Looper.getMainLooper()).idle()
            UiSupport.nav(c.get(), "overview"); UiSupport.nav(c.get(), "permissions")
            tag(c, "scores_bundled_retry").single().performClick(); answerPassword("Parent12345")
            assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun aSpaceMessageNeverShowsMoreFreeSpaceThanNeededAndSaysAboutOnePointOneGb() {
        RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
        val low = ScoresChildCopy.result(RiskImportResult.NOT_ENOUGH_SPACE, false, true, 400L * 1024 * 1024)!!.second
        assertTrue(low, low.contains("about 1.1 GB") && low.contains("has 0.4 GB") && low.contains("需要約 1.1 GB"))
        val midway = ScoresChildCopy.result(RiskImportResult.NOT_ENOUGH_SPACE, false, true, 5L * 1024 * 1024 * 1024)!!.second
        assertTrue(midway, midway.contains("about 1.1 GB") && midway.contains("ran out while copying") && !midway.contains("5.0 GB") && !midway.contains("has "))
        assertTrue(ScoresChildCopy.result(RiskImportResult.NOT_ENOUGH_SPACE, false, true, -1L)!!.second.contains("ran out while copying"))
    }
    @Test fun aNewAppInstallClearsTheAutoInstallBlock() {
        asset(tiny.copyOf(5))
        assertTrue(BundledModel.maybeAutoInstall(ctx)); assertEquals(RiskImportResult.WRONG_FILE, ModelImportJob.result)
        ModelImportJob.dismissResult(); asset()
        assertFalse(BundledModel.maybeAutoInstall(ctx)); assertTrue(RiskModelStore.autoBlocked(ctx))
        shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName).lastUpdateTime += 1000   // the app was updated
        assertFalse(RiskModelStore.autoBlocked(ctx))
        assertTrue(BundledModel.maybeAutoInstall(ctx)); assertEquals(RiskModelState.INSTALLED, RiskModelStore.state(ctx))
    }
    @Test fun theParentIsToldTheModelIsValidAfterABundledInstall() {
        val authority = FamilySecurity.newSigningIdentity(); val id = UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key", authority.publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
        val token = GuardEngine.getOrCreateFamilyToken(ctx); val scope = GuardEngine.getChannelTopic(ctx)
        fun models() = FamilyEvidence(ctx).use { db -> db.readableDatabase.rawQuery("SELECT wire FROM outbox WHERE scope=?", arrayOf(scope)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
            .mapNotNull { GuardEngine.decryptPayload(token, it) }.filter { it.optBoolean("relay_control") }.map { it.getJSONObject("envelope") }
            .filter { it.optString("type") == "scores_mode_report" }.map { it.getJSONObject("state").getString("model") }
        assertEquals(emptyList<String>(), models())
        asset(); assertTrue(BundledModel.maybeAutoInstall(ctx))
        assertEquals(listOf("valid"), models())
    }
}
