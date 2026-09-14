package cn.local.dailywork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyTrackingValidationTest {
    @Test
    fun trackingInputsAcceptPositiveBoundedValues() {
        assertEquals(90, validLearningMinutes(90))
        assertNull(validLearningMinutes(0))
        assertEquals(500, validWaterMilliliters(500))
        assertNull(validWaterMilliliters(-1))
        assertEquals(12000, validSteps(12000))
        assertNull(validSteps(-2))
    }
}
