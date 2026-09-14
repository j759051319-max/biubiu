package cn.local.dailywork.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

enum class HealthConnectStatus { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

internal fun mapSdkStatus(status: Int): HealthConnectStatus = when (status) {
    HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NEEDS_INSTALL
    else -> HealthConnectStatus.UNAVAILABLE
}

class StepHealthGateway(private val context: Context) {
    fun status(): HealthConnectStatus = mapSdkStatus(HealthConnectClient.getSdkStatus(context))

    fun permission(): String = HealthPermission.getReadPermission(StepsRecord::class)

    suspend fun hasPermission(): Boolean = status() == HealthConnectStatus.AVAILABLE &&
        HealthConnectClient.getOrCreate(context)
            .permissionController
            .getGrantedPermissions()
            .contains(permission())

    suspend fun readSteps(date: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val response = HealthConnectClient.getOrCreate(context).aggregate(
            AggregateRequest(
                metrics = setOf<AggregateMetric<Long>>(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    date.atStartOfDay(zone).toInstant(),
                    date.plusDays(1).atStartOfDay(zone).toInstant()
                )
            )
        )
        return response[StepsRecord.COUNT_TOTAL] ?: 0L
    }
}
