package app.family.igguard

import android.app.Application
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import app.family.igguard.ChildPairingState as CS

/** The QR pairing screens on both roles, both themes: every state renders without clipping, written as PNGs for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrPairingUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = LocalVault.preferences(ctx)
    @Before fun reset() {
        prefs.edit().clear().commit(); ctx.deleteDatabase("family_evidence.db")
        TestKeyStore.privateKeys.clear(); TestKeyStore.certificates.clear()
        ParentUiAuthFixture.autoApprove = true
    }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun call(a: MainActivity, name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(a, *args)
    private fun field(a: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun theme(a: MainActivity) = call(a, "currentTheme")
    private fun sheet(a: MainActivity) = field(a, "activeSheet").get(a) as Dialog

    /** Draws the sheet at phone width; fails if a line runs past its view by more than one character (CJK punctuation may hang by one). */
    private fun save(dialog: Dialog, name: String) {
        idle()
        val root = dialog.window!!.decorView
        val width = 390
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
        val clipped = all(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() && it !is EditText }
            .filter { text -> text.layout != null && (0 until text.layout.lineCount).any { text.layout.getLineWidth(it) > text.width - text.totalPaddingLeft - text.totalPaddingRight + text.textSize } }
            .map { "'${it.text.toString().take(30)}' w=${it.width}" }
        val bitmap = Bitmap.createBitmap(width, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File("build/test-screenshots/$name.png"); out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        assertTrue("$name: clipped $clipped", clipped.isEmpty())
    }
    private fun suffix(bright: Boolean) = if (bright) "light" else "dark"

    @Test fun parentPairingSheetStatesRenderInBothThemes() {
        for (bright in listOf(true, false)) {
            prefs.edit().clear().commit()
            GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); GuardEngine.setBrightTheme(ctx, bright)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                call(a, "showParentPairingSheet")
                val dialog = sheet(a)
                assertTrue("FLAG_SECURE", dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue("screen kept on", dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                save(dialog, "qr-parent-unreachable-${suffix(bright)}")
                val body = all(dialog.window!!.decorView).first { it.tag == "pairing_stopped" }.parent as LinearLayout
                var clock = System.currentTimeMillis()
                val rooms = FakeRooms()
                var link: ParentPairingLink? = null
                link = ParentPairingLink(a, { link?.let { call(a, "fillParentPairing", body, dialog, it, theme(a)) } }, rooms.opener, { clock })
                field(a, "parentPairing").set(a, link)
                link.begin(); save(dialog, "qr-parent-connecting-${suffix(bright)}")
                rooms.rooms[0].ready()
                assertNotNull(all(dialog.window!!.decorView).firstOrNull { it.tag == "pairing_qr" })
                save(dialog, "qr-parent-qr-${suffix(bright)}")
                val child = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)
                rooms.rooms[0].deliver(child.start(link.qrText!!, "Pixel 7", clock + 1_000)!!.hello)
                assertNull("the QR left the screen", all(dialog.window!!.decorView).firstOrNull { it.tag == "pairing_qr" })
                assertTrue(child.onReply(rooms.rooms[0].records().single(), clock + 2_000))
                val digits = all(dialog.window!!.decorView).filterIsInstance<EditText>().single { it.tag == "pairing_digits" }
                assertEquals(InputType.TYPE_CLASS_NUMBER, digits.inputType)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, digits.importantForAutofill)
                assertTrue("at most 6 digits", digits.filters.any { it is android.text.InputFilter.LengthFilter && it.max == 6 })
                val accept = all(dialog.window!!.decorView).filterIsInstance<Button>().single { it.tag == "pairing_accept" }
                assertFalse("accept stays disabled until the digits match", accept.isEnabled); assertTrue(accept is ObscuredAwareButton)
                save(dialog, "qr-parent-digits-${suffix(bright)}")
                val right = (child.state as CS.ShowingDigits).digits
                val wrong = if (right == "000000") "000001" else "000000"
                all(dialog.window!!.decorView).filterIsInstance<EditText>().single { it.tag == "pairing_digits" }.setText(wrong); idle()
                val cleared = all(dialog.window!!.decorView).filterIsInstance<EditText>().single { it.tag == "pairing_digits" }
                assertEquals("cleared after a wrong entry", "", cleared.text.toString())
                assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.tag == "pairing_tries" && it.text.contains("尚餘 2 次") })
                save(dialog, "qr-parent-digits-wrong-${suffix(bright)}")
                cleared.setText(right); idle()
                assertTrue(all(dialog.window!!.decorView).filterIsInstance<Button>().single { it.tag == "pairing_accept" }.isEnabled)
                save(dialog, "qr-parent-ready-${suffix(bright)}")
                // The accept step is confirmed with the device's own lock, never a typed parent password.
                val acceptTexts = all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(acceptTexts.any { it.contains("用此手機的螢幕鎖或生物辨識確認") && it.contains("this phone's screen lock or biometric") })
                assertTrue(acceptTexts.none { it.contains("密碼") || it.contains("password", ignoreCase = true) })
                assertTrue(link.accept())
                save(dialog, "qr-parent-finishing-${suffix(bright)}")
                // Cancelled states on fresh links.
                for ((name, finish) in listOf<Pair<String, (ParentPairingLink, FakeRooms) -> Unit>>(
                        "expired" to { l, r -> r.rooms[0].ready(); clock += PairingCrypto.QR_LIFETIME_MS; l.tick() },
                        "too-old" to { _, r -> r.rooms[0].ready(childProof = false) },
                        "wrong-digits" to { l, r -> r.rooms[0].ready()
                            val ch = ChildPairing({ FamilySecurity.createChildSigningKey(ctx) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, QrPairing.palette)
                            r.rooms[0].deliver(ch.start(l.qrText!!, "Pixel 7", clock + 1_000)!!.hello)
                            assertTrue(ch.onReply(r.rooms[0].records().single(), clock + 2_000))
                            val d = (ch.state as CS.ShowingDigits).digits
                            listOf("000000", "000001", "000002", "000003").filter { it != d }.take(3).forEach { l.enterDigits(it) } })) {
                    clock = System.currentTimeMillis()
                    val r2 = FakeRooms(); var l2: ParentPairingLink? = null
                    l2 = ParentPairingLink(a, { l2?.let { call(a, "fillParentPairing", body, dialog, it, theme(a)) } }, r2.opener, { clock })
                    field(a, "parentPairing").set(a, l2)
                    body.tag = null; l2.begin(); finish(l2, r2)
                    assertNotNull(name, all(dialog.window!!.decorView).firstOrNull { it.tag == "pairing_stopped" })
                    save(dialog, "qr-parent-$name-${suffix(bright)}")
                }
            } finally { c.pause().stop().destroy() }
        }
    }

    @Test fun childPairingSheetStatesRenderInBothThemes() {
        for (bright in listOf(true, false)) {
            prefs.edit().clear().commit()
            GuardEngine.setBrightTheme(ctx, bright); shadowOf(ctx).denyPermissions(android.Manifest.permission.CAMERA)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                call(a, "navigateTo", "child_setup")
                assertNull("no camera request before the scan screen", shadowOf(a).lastRequestedPermission)
                call(a, "showChildPairingSheet")
                val dialog = sheet(a)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(android.Manifest.permission.CAMERA, shadowOf(a).lastRequestedPermission.requestedPermissions.single())
                save(dialog, "qr-child-permission-wait-${suffix(bright)}")
                call(a, "onRequestPermissionsResult", 102, arrayOf(android.Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_DENIED))
                assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("需要相機才可掃描家長手機上的 QR 碼。") })
                save(dialog, "qr-child-camera-denied-${suffix(bright)}")
                shadowOf(ctx).grantPermissions(android.Manifest.permission.CAMERA)
                call(a, "onRequestPermissionsResult", 102, arrayOf(android.Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_GRANTED))
                save(dialog, "qr-child-scan-${suffix(bright)}")
                // A decoded QR goes through the scanner callback's own path.
                val parent = FamilySecurity.newSigningIdentity(); val bare = QrPairing.bareParent(parent)
                val start = bare.start(parent.publicKey, System.currentTimeMillis())
                call(a, "onQrScanned", start.qrText)
                assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("只可掃描你自己家長手機上的 QR 碼") })
                assertTrue(all(dialog.window!!.decorView).filterIsInstance<Button>().single { it.tag == "join_family" } is ObscuredAwareButton)
                save(dialog, "qr-child-join-${suffix(bright)}")
                val body = all(dialog.window!!.decorView).first { it.tag == "join_warning" }.parent as LinearLayout
                val rooms = FakeRooms(); var done: ChildPairing.Result? = null
                var link: ChildPairingLink? = null
                link = ChildPairingLink(a, { call(a, "fillChildPairing") }, { done = it }, rooms.opener)
                field(a, "childPairing").set(a, link)
                assertTrue(link.join(start.qrText)); idle()
                save(dialog, "qr-child-waiting-reply-${suffix(bright)}")
                rooms.rooms[0].ready()
                rooms.rooms[0].deliver(bare.onHello(rooms.rooms[0].records().single(), System.currentTimeMillis())!!)
                val shown = all(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.tag == "child_digits" }
                assertEquals((link.machine.state as CS.ShowingDigits).digits, shown.text.toString().replace(" ", ""))
                assertTrue("the digits screen keeps FLAG_SECURE", dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                save(dialog, "qr-child-digits-${suffix(bright)}")
                assertTrue(bare.digitsEntered((link.machine.state as CS.ShowingDigits).digits))
                assertTrue(link.confirm()); idle()
                save(dialog, "qr-child-waiting-parent-${suffix(bright)}")
                val waiting = all(dialog.window!!.decorView)
                assertTrue((waiting.single { it.tag == "child_wait_time" } as TextView).text.toString().let { it.startsWith("已等候 0:0") && (it.contains("尚餘 10:00") || it.contains("尚餘 9:5")) })
                assertTrue((waiting.single { it.tag == "child_wait_hint" } as TextView).text.contains("家長手機可能已停止這次配對"))
                assertTrue(waiting.filterIsInstance<Button>().any { it.text == "取消 · Cancel" && it.isShown })
                rooms.rooms[1].deliver(bare.accept(FamilySecurity.passwordVerifier(QrPairing.PASSWORD), System.currentTimeMillis())!!.complete)
                assertNotNull(done)
                field(a, "childPairingStored").set(a, true); call(a, "fillChildPairing")
                save(dialog, "qr-child-done-${suffix(bright)}")
                val r2 = FakeRooms(); var l2: ChildPairingLink? = null
                l2 = ChildPairingLink(a, { call(a, "fillChildPairing") }, {}, r2.opener)
                field(a, "childPairing").set(a, l2); field(a, "childPairingStored").set(a, null)
                l2.join(bare.let { QrPairing.bareParent(parent).start(parent.publicKey, System.currentTimeMillis()).qrText }); l2.cancel(); idle()
                assertNotNull(all(dialog.window!!.decorView).firstOrNull { it.tag == "child_pairing_stopped" })
                save(dialog, "qr-child-cancelled-${suffix(bright)}")
                // The rendezvous room retired before M2: the parent stopped (cancel or QR expiry).
                val r3 = FakeRooms(); var l3: ChildPairingLink? = null
                l3 = ChildPairingLink(a, { call(a, "fillChildPairing") }, {}, r3.opener)
                field(a, "childPairing").set(a, l3)
                l3.join(QrPairing.bareParent(parent).start(parent.publicKey, System.currentTimeMillis()).qrText); r3.rooms[0].ready()
                r3.rooms[0].route.onRefused(410); idle()
                assertTrue(l3.parentStopped)
                assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("家長手機已停止這次配對") })
                save(dialog, "qr-child-parent-stopped-${suffix(bright)}")
                assertNotNull(body)
            } finally { c.pause().stop().destroy() }
        }
    }

    /** The first "Allow" on the camera prompt must start the scanner without reopening the sheet; "Deny" shows the denied state. */
    @Test fun theScannerStartsAsSoonAsTheCameraIsAllowed() {
        for (allow in listOf(true, false)) {
            prefs.edit().clear().commit(); shadowOf(ctx).denyPermissions(android.Manifest.permission.CAMERA)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                call(a, "navigateTo", "child_setup"); call(a, "showChildPairingSheet")
                val views = { all(sheet(a).window!!.decorView) }
                assertTrue(views().none { it.tag == "qr_scanner" })
                if (allow) shadowOf(ctx).grantPermissions(android.Manifest.permission.CAMERA)
                call(a, "onRequestPermissionsResult", 102, arrayOf(android.Manifest.permission.CAMERA),
                    intArrayOf(if (allow) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
                if (!allow) { assertNotNull(views().firstOrNull { it.tag == "camera_denied" }); assertTrue(views().none { it.tag == "qr_scanner" }); continue }
                val scanner = views().firstOrNull { it.tag == "qr_scanner" } as? QrScannerView
                assertNotNull("the scanner view is built at once", scanner)
                var started = false
                val error = scanner!!.onError; scanner.onError = { started = true; error(it) }
                idle()
                // start() ran: this Robolectric phone has no camera, so it reports that (a real phone opens the preview).
                assertTrue("start() was requested", started)
                assertTrue(views().any { it is TextView && it.text.contains("此手機沒有可用的相機") })
            } finally { c.pause().stop().destroy() }
        }
    }

    /** Large system text on a 360 dp phone: the scan button is inside the first screenful, the warning directly under it. */
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    @Test fun theScanButtonIsOnTheFirstScreenAtLargeFontSizes() {
        for (scale in listOf(1.3f, 1.5f)) {
            prefs.edit().clear().commit(); RuntimeEnvironment.setFontScale(scale)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                call(a, "navigateTo", "child_setup"); idle()
                val shell = field(a, "screenShell").get(a) as ViewGroup
                shell.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)); shell.layout(0, 0, 360, 640)
                val frame = field(a, "contentFrame").get(a) as View
                val button = shell.findViewWithTag<View>("scan_qr")
                val at = android.graphics.Rect(0, 0, button.width, button.height); shell.offsetDescendantRectToMyCoords(button, at)
                val visible = android.graphics.Rect(0, 0, frame.width, frame.height); shell.offsetDescendantRectToMyCoords(frame, visible)
                assertTrue("font scale $scale: button bottom ${at.bottom} inside ${visible.bottom}", at.bottom in 1..visible.bottom)
                val parent = button.parent as ViewGroup
                val warning = parent.getChildAt(parent.indexOfChild(button) + 1)
                assertTrue(all(warning).filterIsInstance<TextView>().any { it.text.toString() == "只可掃描你自己家長手機上的 QR 碼。掃描他人的 QR 碼會把此手機連到該人。\nScan only the QR code on your own parent's phone. Scanning someone else's code connects this phone to that person." })
                val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888); shell.draw(Canvas(bitmap))
                File("build/test-screenshots/child-setup-font-$scale.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { c.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
        }
    }

    @Test fun chatInvitationsAreRefusedOnANewChildPhone() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            val parent = FamilySecurity.newSigningIdentity()
            val v43 = "[IGGUARD-PAIR-V43:01234567-89ab-cdef-0123-456789abcdef:${FamilySecurity.encode(FamilySecurity.passwordVerifier("ParentPassword928!"))}:${parent.publicKey}]"
            val tokenBefore = prefs.getString("family_uuid_token", null)
            call(a, "showChildPairingSheet")
            call(a, "onQrScanned", v43)                                     // V43 text, even when it reaches the scanner
            val views = all(sheet(a).window!!.decorView)
            assertTrue(views.any { it.tag == "scan_problem" }); assertTrue(views.none { it.tag == "join_family" })
            assertEquals("unconfigured", GuardEngine.getRole(ctx)); assertEquals(tokenBefore, prefs.getString("family_uuid_token", null))
            sheet(a).dismiss()
            call(a, "onNewIntent", android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("igguard://pair?version=43&token=01234567-89ab-cdef-0123-456789abcdef&pw=x&parent=${parent.publicKey}")))
            assertNotNull(sheet(a).window!!.decorView.findViewWithTag<View>("invite_refused"))
            save(sheet(a), "qr-child-invite-refused-light")
            assertEquals("unconfigured", GuardEngine.getRole(ctx))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun parentOverviewShowsThePermanentNotVerifiedBannerForA52Pairing() {
        for (bright in listOf(true, false)) {
            prefs.edit().clear().commit()
            GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); GuardEngine.setBrightTheme(ctx, bright)
            GuardEngine.recordChildHeartbeatOnParent(ctx, "裝置 Pixel 運作中", System.currentTimeMillis() - 60_000)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                call(a, "navigateTo", "overview")
                val shell = field(a, "screenShell").get(a) as View
                assertNotNull(shell.findViewWithTag<View>("child_not_verified"))
                assertTrue(all(shell).filterIsInstance<Button>().any { it.text.contains("重新配對以核實") })
                shell.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY)); shell.layout(0, 0, 390, 844)
                val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888); shell.draw(Canvas(bitmap))
                File("build/test-screenshots/qr-parent-banner-not-verified-${suffix(bright)}.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { c.pause().stop().destroy() }
        }
    }

    /** The overview for a parent state with no paired child: [tag]'s banner, [card] on the child card, nothing that looks verified or waiting. */
    private fun unpairedOverview(mark: String, tag: String, card: String, banner: List<String>) {
        for (bright in listOf(true, false)) {
            prefs.edit().clear().commit()
            GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD); GuardEngine.setBrightTheme(ctx, bright)
            prefs.edit().putBoolean(mark, true).commit()
            val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
            try {
                call(a, "navigateTo", "overview")
                val shell = field(a, "screenShell").get(a) as View
                val note = shell.findViewWithTag<View>(tag)
                assertNotNull(note)
                val noteText = all(note).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                for (part in banner) assertTrue("$tag: '$part' in $noteText", noteText.contains(part))
                val texts = all(shell).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts.contains(card))
                assertTrue(texts.none { it.contains("等待子女手機") || it.contains("已收到子女回報") || it.contains("子女回報逾時") })
                assertNull(shell.findViewWithTag<View>("child_not_verified"))
                assertTrue(all(shell).filterIsInstance<Button>().any { it.text.contains("重新配對 · Pair again") })
                shell.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY)); shell.layout(0, 0, 390, 844)
                val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888); shell.draw(Canvas(bitmap))
                File("build/test-screenshots/qr-parent-banner-$tag-${suffix(bright)}.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { c.pause().stop().destroy() }
        }
    }

    @Test fun aPendingPairingCanBeCancelledAndPairedAgain() {
        GuardEngine.setRole(ctx, "parent"); GuardEngine.setPin(ctx, QrPairing.PASSWORD)
        prefs.edit().putString("pinned_child_key", FamilySecurity.canonicalKeyText(FamilySecurity.newSigningIdentity().publicKey))
            .putLong("child_pairing_pending_ms", System.currentTimeMillis()).commit()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        try {
            call(a, "navigateTo", "overview")
            val shell = field(a, "screenShell").get(a) as View
            assertNotNull(shell.findViewWithTag<View>("child_pairing_pending"))
            val cancel = shell.findViewWithTag<Button>("cancel_pairing")
            assertTrue("overlay-protected", cancel is ObscuredAwareButton); assertTrue(cancel.text.contains("取消並重新配對 · Cancel and pair again"))
            shadowOf(ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).setIsDeviceSecure(true)
            cancel.performClick(); idle()
            assertNotNull("nothing happens before the fresh authentication", GuardEngine.pinnedChildKey(ctx))
            MainActivity::class.java.getDeclaredMethod("completeParentAuthentication", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, true); idle()
            assertNull(GuardEngine.pinnedChildKey(ctx)); assertFalse(GuardEngine.childPairingPending(ctx)); assertTrue(GuardEngine.childPairingCancelled(ctx))
            assertTrue("the QR sheet opens", sheet(a).isShowing)
            sheet(a).dismiss(); call(a, "navigateTo", "overview"); idle()
            val note = (field(a, "screenShell").get(a) as View).findViewWithTag<View>("child_pairing_failed")
            val text = all(note).filterIsInstance<TextView>().joinToString { it.text.toString() }
            assertTrue(text, text.contains("配對已取消") && text.contains("沒有與任何子女手機配對")); assertFalse(text.contains("上次配對未完成"))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun parentOverviewSaysPlainlyThatTheChildWasRemotelyReset() =
        unpairedOverview("child_remote_reset", "child_remote_reset", "已遠端重設 · Remotely reset",
            listOf("子女手機已遠端重設", "沒有與任何子女手機配對", "請用 QR 碼重新配對", "The child phone was remotely reset", "not paired with any child phone", "Pair again with the QR code"))

    @Test fun parentOverviewSaysPlainlyThatARolledBackPhoneIsNotPaired() =
        unpairedOverview("child_pairing_failed", "child_pairing_failed", "未與任何子女手機配對 · Not paired with any child phone",
            listOf("上次配對未完成", "此手機現時沒有與任何子女手機配對", "之前的配對亦已結束", "請用 QR 碼重新配對",
                "This phone is now not paired with any child phone", "any earlier pairing has ended", "Pair again with the QR code"))
}
