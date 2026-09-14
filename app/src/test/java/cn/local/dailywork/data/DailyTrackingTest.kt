package cn.local.dailywork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyTrackingTest {
    @Test
    fun sleepCrossingMidnightIsCalculated() {
        assertEquals(450, sleepDurationMinutes(7 * 60, 23 * 60 + 30))
    }

    @Test
    fun manualStepsOverrideSyncedSteps() {
        assertEquals(StepDisplay(3200, StepSource.MANUAL), resolveSteps(7000, 3200))
    }

    @Test
    fun healthStepsAreUsedWithoutManualValue() {
        assertEquals(StepDisplay(7000, StepSource.HEALTH_CONNECT), resolveSteps(7000, null))
    }

    @Test
    fun missingSleepTimeHasNoDuration() {
        assertNull(sleepDurationMinutes(7 * 60, null))
    }
}
