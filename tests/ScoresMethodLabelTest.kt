package app.family.igguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual upload uses only Instagram's own export tool, so the chooser recommends it (review 2.4). Nothing is pre-selected. */
class ScoresMethodLabelTest {
    @Test fun theChooserRecommendsManualUploadOnly() {
        assertTrue(ScoresUi.chooserLabel(ScoresMethod.UPLOAD).contains("recommended"))
        assertTrue(ScoresUi.chooserLabel(ScoresMethod.UPLOAD).contains("建議"))
        assertFalse(ScoresUi.chooserLabel(ScoresMethod.CAPTURE).contains("recommended"))
        assertFalse("status lines keep the plain name", ScoresUi.methodName(ScoresMethod.UPLOAD).contains("recommended"))
    }
}
