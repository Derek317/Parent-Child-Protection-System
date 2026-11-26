package app.family.igguard

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RiskScoreReportTest {
    @Test fun exactCutoffsBelongToTheHigherBand() {
        listOf(24.999 to RiskBand.LOW, 25.0 to RiskBand.POTENTIAL, 49.999 to RiskBand.POTENTIAL,
            50.0 to RiskBand.MODERATE, 74.999 to RiskBand.MODERATE, 75.0 to RiskBand.HIGH)
            .forEach { (value, expected) -> assertEquals(expected, RiskBand.forScore(value)) }
    }
}
