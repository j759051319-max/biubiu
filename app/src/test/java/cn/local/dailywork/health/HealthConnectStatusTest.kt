package cn.local.dailywork.health

import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectStatusTest {
    @Test
    fun sdkStatusIsExposedAsUserState() {
        assertEquals(HealthConnectStatus.AVAILABLE, mapSdkStatus(HealthConnectClient.SDK_AVAILABLE))
        assertEquals(
            HealthConnectStatus.NEEDS_INSTALL,
            mapSdkStatus(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
        )
        assertEquals(HealthConnectStatus.UNAVAILABLE, mapSdkStatus(HealthConnectClient.SDK_UNAVAILABLE))
    }
}
