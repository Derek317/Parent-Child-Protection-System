package app.family.igguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {
    @Test fun firstLaunchConsentRendersTheDisclosuresAndChoiceOnPhone() {
        val ctx=RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE).edit().clear().commit()
        LocalVault.preferences(ctx).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val page=controller.get().findViewById<View>(android.R.id.content)
        val width=390;val height=844
        page.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        page.layout(0,0,width,height)
        fun save(name:String) {
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            page.draw(Canvas(bitmap))
            val out=File("build/test-screenshots/$name.png");out.parentFile?.mkdirs()
            out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        save("native-consent-disclosures")
        val scroll=page.findViewWithTag<android.widget.ScrollView>("first_launch_consent")
        scroll.scrollTo(0,scroll.getChildAt(0).height)
        save("native-consent-choice")
        assertNotNull(page.findViewWithTag<View>("consent_accept"))
        controller.stop().destroy()
    }
    @Test fun realEmptyStateRendersBothThemesAndChildPermissions() {
        val ctx=RuntimeEnvironment.getApplication()
        LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setRole(ctx,"unconfigured")
        render("role","native-role",390,844)
        for (bright in listOf(true,false)) {
            GuardEngine.setRole(ctx,"parent"); GuardEngine.setBrightTheme(ctx,bright)
            render("overview",if(bright) "native-parent-light" else "native-parent-dark",390,844)
        }
        GuardEngine.setBrightTheme(ctx,true);GuardEngine.setRole(ctx,"child")
        render("permissions","native-child-permissions",390,844)
        render("unlock","native-child-unlock",390,844)
    }
    @Test fun aRefreshCutShortByPauseDoesNotSilenceLaterRefreshes() {
        val ctx=RuntimeEnvironment.getApplication()
        LocalVault.preferences(ctx).edit().clear().commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try {
            val receiver=MainActivity::class.java.getDeclaredField("refreshReceiver").apply{isAccessible=true}.get(a) as android.content.BroadcastReceiver
            val pending=MainActivity::class.java.getDeclaredField("refreshPending").apply{isAccessible=true}
            receiver.onReceive(a,android.content.Intent(GuardEngine.ACTION_UI_REFRESH))
            assertTrue(pending.getBoolean(a))
            c.pause();c.resume()   // Home and back within the 200 ms: onPause drops the runnable that clears the flag
            assertFalse("later refresh broadcasts would be dropped",pending.getBoolean(a))
        } finally {c.pause().stop().destroy()}
    }
    private fun render(section: String, name: String, width: Int, height: Int) {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,section)
        val shell=MainActivity::class.java.getDeclaredField("screenShell").apply{isAccessible=true}.get(a) as View
        shell.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));shell.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);shell.draw(Canvas(bitmap))
        assertEquals(width,bitmap.width)
        val out=File("build/test-screenshots/$name.png");out.parentFile?.mkdirs();out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle();c.stop().destroy()
    }
}

/** Light and dark renders of the screens rebuilt on the theme tokens: consent, parent lock, storage failure, a sheet's Close, a disabled button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemedScreensRenderTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun suffix(bright: Boolean) = if (bright) "light" else "dark"
    private fun content(a: MainActivity) = a.findViewById<View>(android.R.id.content)
    private fun views(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun firstRunConsentInBothThemes() {
        for (bright in listOf(true, false)) {
            ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE).edit().clear().commit()
            LocalVault.preferences(ctx).edit().clear().commit(); GuardEngine.setBrightTheme(ctx, bright)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val page = content(c.get())
                ShotSupport.save(page, "tier1-consent-top-" + suffix(bright), 390, 844)
                val scroll = page.findViewWithTag<android.widget.ScrollView>("first_launch_consent")
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                ShotSupport.save(page, "tier1-consent-choice-disabled-" + suffix(bright), 390, 844)
                for (tag in AppConsent.acknowledgments) page.findViewWithTag<android.widget.CheckBox>(tag).isChecked = true
                assertTrue(page.findViewWithTag<View>("consent_accept").isEnabled)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                ShotSupport.save(page, "tier1-consent-choice-ready-" + suffix(bright), 390, 844)
            } finally { c.stop().destroy() }
        }
        GuardEngine.setBrightTheme(ctx, true)
    }
    @Test fun parentLockInBothThemes() {
        ParentUiAuthFixture.autoApprove = false
        try {
            for (bright in listOf(true, false)) {
                LocalVault.preferences(ctx).edit().clear().commit(); GuardEngine.setRole(ctx, "parent"); GuardEngine.setBrightTheme(ctx, bright)
                val c = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    assertTrue(views(c.get().window.decorView).any { it.tag == "parent_privacy_lock" })
                    ShotSupport.save(content(c.get()), "tier1-parent-lock-" + suffix(bright), 390, 844)
                } finally { c.pause().stop().destroy() }
            }
        } finally { ParentUiAuthFixture.autoApprove = true; GuardEngine.setBrightTheme(ctx, true) }
    }
    @Test fun storageFailureInBothThemes() {
        val raw = ctx.getSharedPreferences("ig_guard_prefs", Context.MODE_PRIVATE)
        for (bright in listOf(true, false)) {
            ctx.deleteDatabase("family_evidence.db"); raw.edit().clear().commit()
            LocalVault.preferences(ctx).edit().putString("role", "parent").putString("parent_master_password", "SyntheticOnly928!").commit()
            GuardEngine.setBrightTheme(ctx, bright)
            raw.edit().putString("parent_master_password", "corrupted encrypted value").commit()
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                assertTrue(views(c.get().window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("無法開啟加密儲存空間") })
                ShotSupport.save(content(c.get()), "tier1-storage-failure-" + suffix(bright), 390, 844)
            } finally { c.pause().stop().destroy() }
        }
        raw.edit().clear().commit(); GuardEngine.setBrightTheme(ctx, true)
    }
    @Test fun alertDetailSheetWithSecondaryCloseInBothThemes() {
        for (bright in listOf(true, false)) {
            ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
            GuardEngine.setFamilyToken(ctx, java.util.UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "parent"); GuardEngine.setBrightTheme(ctx, bright)
            GuardEngine.recordParentAlert(ctx, org.json.JSONObject().put("type", "alert").put("event_id", "e1").put("sender", "test_account_a").put("text", "Synthetic test message").put("reason", "stranger_message").put("ts_ms", System.currentTimeMillis()), "relay:e1")
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                val t = MainActivity::class.java.getDeclaredMethod("currentTheme").apply { isAccessible = true }.invoke(a)
                MainActivity::class.java.declaredMethods.first { it.name == "showAlertDetails" }.apply { isAccessible = true }.invoke(a, GuardEngine.getParentAlerts(ctx).first(), t)
                ShotSupport.shootDialog("tier1-sheet-alert-detail-" + suffix(bright), 390, 1800)
            } finally { c.pause().stop().destroy() }
        }
        GuardEngine.setBrightTheme(ctx, true)
    }
    @Test fun rolePageWithDisabledContinueInBothThemes() {
        for (bright in listOf(true, false)) {
            ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
            GuardEngine.setRole(ctx, "unconfigured"); GuardEngine.setBrightTheme(ctx, bright)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                UiSupport.nav(c.get(), "role")
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertFalse(views(c.get().window.decorView).single { it.tag == "continue_role" }.isEnabled)
                ShotSupport.shoot(c.get(), "tier1-role-disabled-continue-" + suffix(bright), 390, 844)
            } finally { c.pause().stop().destroy() }
        }
        GuardEngine.setBrightTheme(ctx, true)
    }
}

/** Seeds and renders the scores-only screens to PNG. Labels are invented; no message text is ever seeded. */
internal object ShotSupport {
    val ctx get() = RuntimeEnvironment.getApplication()
    fun save(view: android.view.View, name: String, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val out = File("build/test-screenshots/$name.png"); out.parentFile?.mkdirs(); out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    fun shell(a: MainActivity) = MainActivity::class.java.getDeclaredField("screenShell").apply { isAccessible = true }.get(a) as View
    fun shoot(a: MainActivity, name: String, width: Int, height: Int) = save(shell(a), name, width, height)
    fun shootDialog(name: String, width: Int, height: Int) {
        val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        save(root, name, width, root.measuredHeight.coerceAtLeast(100))
    }
    fun parentReset() {
        ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx, java.util.UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "parent"); GuardEngine.setBrightTheme(ctx, true)
    }
    fun report(model: String = "valid", consent: String = "accepted", paused: Boolean = false, rows: List<ScoresReportRow> = emptyList(), receivedAt: Long = System.currentTimeMillis()) {
        val scope = GuardEngine.getChannelTopic(ctx); val sent = System.currentTimeMillis() - 1000
        val state = ChildScoresState(GuardEngine.scoresModePolicy(ctx)?.policyId, model, consent, paused, if (paused) sent - 3_600_000L else null, sent - 100, rows.size, 2, true)
        FamilyEvidence(ctx).use { it.storeScoresReport(scope, ParsedScoresReport(java.util.UUID.randomUUID().toString(), sent, state, rows), receivedAt) }
    }
    fun enable() { report(); check(GuardEngine.sendScoresModePolicy(ctx, true)) }
    fun rows(now: Long = System.currentTimeMillis()) = listOf(
        ScoresReportRow("test_account_a", 91.0, null, now - 3_600_000L), ScoresReportRow("test_account_b", 62.4, null, now - 26 * 3_600_000L),
        ScoresReportRow("test_account_c", 33.0, null, now - 50 * 3_600_000L), ScoresReportRow("test_account_d", 8.0, null, now - 70 * 3_600_000L),
        ScoresReportRow("test_account_e", null, "insufficient_context", now - 5_000_000L))
    fun open(section: String): org.robolectric.android.controller.ActivityController<MainActivity> {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        UiSupport.nav(c.get(), section)
        if (section == "scores") UiSupport.views(c.get(), "scores_loading")
        // Let the screen-change animation finish so the new screen, not the old one, is drawn.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        return c
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoresScreenshotTest {
    private val ctx get() = ShotSupport.ctx
    @org.junit.Before fun reset() { RuntimeEnvironment.setFontScale(1f); ShotSupport.parentReset() }

    private fun parentShot(name: String, section: String = "scores", height: Int = 800, bright: Boolean = true, seed: () -> Unit) {
        ShotSupport.parentReset(); GuardEngine.setBrightTheme(ctx, bright); seed()
        val c = ShotSupport.open(section)
        try { ShotSupport.shoot(c.get(), name, 360, height) } finally { c.pause().stop().destroy() }
    }
    @Test fun parentListLightAndDarkWithStaleBanner() {
        val received = System.currentTimeMillis() - 80L * 3_600_000L
        for (bright in listOf(true, false)) parentShot(if (bright) "scores-parent-list-light" else "scores-parent-list-dark", height = 1900, bright = bright) {
            ShotSupport.report(rows = ShotSupport.rows(received), receivedAt = received); check(GuardEngine.sendScoresModePolicy(ctx, true))
            ShotSupport.report(rows = ShotSupport.rows(received), receivedAt = received)
        }
    }
    @Test fun parentWaitingAndPaused() {
        parentShot("scores-parent-waiting-light", height = 1100) { ShotSupport.report(model = "valid"); check(GuardEngine.sendScoresModePolicy(ctx, true)) }
        parentShot("scores-parent-paused-light", height = 1500) {
            ShotSupport.report(); check(GuardEngine.sendScoresModePolicy(ctx, true)); ShotSupport.report(paused = true, rows = ShotSupport.rows().take(2))
        }
    }
    @Test fun parentSwitchRowOnAndChangeOfModeSheet() {
        parentShot("scores-parent-switch-on", section = "family", height = 1300) { ShotSupport.enable() }
        ShotSupport.parentReset(); ShotSupport.report()
        val c = ShotSupport.open("family")
        try {
            UiSupport.all(c.get().window.decorView).first { it.tag == "scores_mode_switch" }.performClick()
            ShotSupport.shootDialog("scores-parent-change-of-mode-sheet", 360, 1400)
        } finally { c.pause().stop().destroy() }
    }
    // ---------------- child ----------------
    private val tiny = "tiny model file for tests".toByteArray()
    private fun childReset() {
        ctx.deleteDatabase("family_evidence.db"); LocalVault.preferences(ctx).edit().clear().commit()
        ctx.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE).edit().clear().commit()
        RiskModelStore.directory(ctx).deleteRecursively()
        RiskModelStore.memoryProbe = { 8L shl 30 }; RiskModelStore.abiProbe = { true }
        GuardEngine.setBrightTheme(ctx, true); GuardEngine.setFamilyToken(ctx, java.util.UUID.randomUUID().toString()); GuardEngine.setRole(ctx, "child")
        val authority = FamilySecurity.newSigningIdentity(); val id = java.util.UUID.randomUUID().toString()
        LocalVault.preferences(ctx).edit().putString("parent_command_public_key", authority.publicKey).putString("capture_policy_id", id).putString("capture_local_ack_id", id).commit()
    }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun install() {
        RiskModelStore.expectation = RiskModelStore.Expectation(tiny.size.toLong(), sha(tiny))
        RiskModelStore.importModel(ctx, java.io.ByteArrayInputStream(tiny), {}, tiny.size.toLong(), sha(tiny)); RiskLocal.changed(ctx)
    }
    @Test fun childScreens() {
        try {
            childReset(); install(); RiskLocal.receivePolicy(ctx, java.util.UUID.randomUUID().toString(), true); RiskLocal.accept(ctx); RiskLocal.acknowledgeChangeNotice(ctx)
            RiskLocal.recordRun(ctx, 14, System.currentTimeMillis() - 3_600_000L)
            var c = ShotSupport.open("overview")
            try { ShotSupport.shoot(c.get(), "scores-child-overview-status-on", 360, 1500) } finally { c.pause().stop().destroy() }

            childReset(); install(); RiskLocal.receivePolicy(ctx, java.util.UUID.randomUUID().toString(), true)
            c = ShotSupport.open("overview")
            try { ShotSupport.shoot(c.get(), "scores-child-overview-consent-card", 360, 1500) } finally { c.pause().stop().destroy() }
            c = ShotSupport.open("scores_consent")
            try { ShotSupport.shoot(c.get(), "scores-child-consent-page", 360, 2600) } finally { c.pause().stop().destroy() }

            childReset(); RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
            ModelImportJob.runner = { it.run() }; ModelImportJob.dismissResult()
            c = ShotSupport.open("permissions")
            try {
                val uri = android.net.Uri.parse("content://test.picker/model")
                org.robolectric.Shadows.shadowOf(ctx.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(tiny))
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.Intent::class.java).apply { isAccessible = true }
                    .invoke(c.get(), 9032, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                ShotSupport.shoot(c.get(), "scores-child-import-wrong-file", 360, 1700)
            } finally { c.pause().stop().destroy() }

            // The model included in the app: the install row, the progress card, and the not-enough-space result.
            childReset(); RiskModelStore.expectation = RiskModelStore.Expectation(tiny.size.toLong(), sha(tiny))
            RiskModelStore.bundledOpener = { java.io.ByteArrayInputStream(tiny) }; RiskModelStore.blockAuto(ctx)
            ModelImportJob.runner = { it.run() }; ModelImportJob.dismissResult(); ModelImportJob.bundled = false
            c = ShotSupport.open("permissions")
            try {
                ShotSupport.shoot(c.get(), "scores-child-bundled-install-row", 360, 1500)
            } finally { c.pause().stop().destroy() }
            ModelImportJob.running = true; ModelImportJob.bundled = true; ModelImportJob.copied = RiskModelSpec.FILE_BYTES * 2 / 5
            c = ShotSupport.open("permissions")
            try { ShotSupport.shoot(c.get(), "scores-child-bundled-install-progress", 360, 1000) } finally { c.pause().stop().destroy() }
            ModelImportJob.running = false; ModelImportJob.copied = 0L; ModelImportJob.dismissResult()
            RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
            RiskModelStore.freeSpaceProbe = { 400L * 1024 * 1024 }
            ModelImportJob.startBundled(ctx)
            c = ShotSupport.open("permissions")
            try { ShotSupport.shoot(c.get(), "scores-child-bundled-not-enough-space", 360, 1100) } finally { c.pause().stop().destroy() }
        } finally {
            RiskModelStore.bundledOpener = { null }; RiskModelStore.freeSpaceProbe = { it.usableSpace }; ModelImportJob.running = false; ModelImportJob.bundled = false
            ModelImportJob.runner = { Thread(it, "Model import").start() }; ModelImportJob.dismissResult()
            RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
            RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        }
    }
    /** Tier 1 collection: the child's upload card (guide link, export button, picker row, a good import), its guide sheet (steps, optional cloud export) and the parent's freshness line. */
    @Test fun collectionUploadCardAndParentFreshnessInBothThemes() {
        try {
            for (bright in listOf(true, false)) {
                val s = if (bright) "light" else "dark"
                childReset(); install(); GuardEngine.setBrightTheme(ctx, bright)
                RiskLocal.receivePolicy(ctx, java.util.UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD); RiskLocal.accept(ctx); RiskLocal.acknowledgeChangeNotice(ctx)
                InstagramImport.last = ImportSummary(ImportResult.OK, 3, 1, 42)
                val c = ShotSupport.open("overview")
                try {
                    ShotSupport.shoot(c.get(), "tier1-collection-child-upload-$s", 360, 3400)
                    UiSupport.all(c.get().window.decorView).single { it.tag == "scores_export_guide_link" }.performClick()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    ShotSupport.shootDialog("tier1-collection-child-guide-sheet-$s", 360, 2400)
                } finally { c.pause().stop().destroy() }
                InstagramImport.dismiss()
                val old = System.currentTimeMillis() - 10 * 86_400_000L
                parentShot("tier1-collection-parent-scores-$s", height = 1900, bright = bright) {
                    ShotSupport.report(); check(GuardEngine.sendScoresModePolicy(ctx, true, ScoresMethod.UPLOAD)); ShotSupport.report(rows = ShotSupport.rows(old))
                }
            }
        } finally {
            InstagramImport.dismiss(); GuardEngine.setBrightTheme(ctx, true)
            RiskModelStore.expectation = RiskModelStore.Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
            RiskModelStore.abiProbe = { android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoresScreenshotLargeTextTest {
    @Test fun parentListAtFontScale15And320dp() {
        RuntimeEnvironment.setFontScale(1.5f)
        try {
            ShotSupport.parentReset(); ShotSupport.report(); check(GuardEngine.sendScoresModePolicy(ShotSupport.ctx, true)); ShotSupport.report(rows = ShotSupport.rows())
            val c = ShotSupport.open("scores")
            try { ShotSupport.shoot(c.get(), "scores-parent-list-320dp-font15", 320, 3600) } finally { c.pause().stop().destroy() }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
