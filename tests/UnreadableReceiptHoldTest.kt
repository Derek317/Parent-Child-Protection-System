package app.family.igguard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A receipt that is present but cannot be read is not a receipt for capture: a switch-off must hold capture, and the stored text must go at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnreadableReceiptHoldTest : UploadTestBase() {
    @Test fun aSwitchOffWithAnUnreadableReceiptHoldsCapture() {
        for (method in listOf(ScoresMethod.UPLOAD, ScoresMethod.CAPTURE)) {
            resetChild(); activate(method)
            receipts.edit().putString("receipt", "not a sealed receipt").commit(); assertNull(RiskLocal.agreedMethod(ctx))
            switchOff()
            assertTrue("$method: held", RiskLocal.captureHeld(ctx)); assertTrue(RiskLocal.uploadOnly(ctx)); assertEquals(RiskCapture.UPLOAD, RiskLocal.captureMode(ctx))
        }
    }
    @Test fun noReceiptAndAReadableCaptureReceiptHoldNothing() {
        installModel(); RiskLocal.receivePolicy(ctx, java.util.UUID.randomUUID().toString(), true, ScoresMethod.UPLOAD); switchOff()   // asked for upload, never agreed
        assertFalse(RiskLocal.captureHeld(ctx)); assertEquals(RiskCapture.NORMAL, RiskLocal.captureMode(ctx))
        resetChild(); activate(ScoresMethod.CAPTURE); switchOff()
        assertFalse(RiskLocal.captureHeld(ctx)); assertEquals(RiskCapture.NORMAL, RiskLocal.captureMode(ctx))
    }
}
