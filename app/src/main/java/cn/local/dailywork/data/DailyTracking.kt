package cn.local.dailywork.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "learning_sessions", indices = [Index("date")])
data class LearningSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val durationMinutes: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "water_entries", indices = [Index("date")])
data class WaterEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val milliliters: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "daily_wellness")
data class DailyWellness(
    @PrimaryKey val date: String,
    val wakeMinute: Int? = null,
    val bedMinute: Int? = null,
    val manualSteps: Int? = null,
    val syncedSteps: Long? = null,
    val stepsSyncedAt: Long? = null
)

enum class StepSource { HEALTH_CONNECT, MANUAL, UNAVAILABLE }

data class StepDisplay(val value: Long?, val source: StepSource)

data class DailyTrackingSummary(
    val learningMinutes: Int,
    val waterMilliliters: Int,
    val sleepMinutes: Int?,
    val steps: StepDisplay
)

fun sleepDurationMinutes(wakeMinute: Int?, bedMinute: Int?): Int? {
    if (wakeMinute == null || bedMinute == null) return null
    return (wakeMinute - bedMinute).mod(24 * 60)
}

fun resolveSteps(healthSteps: Long?, manualSteps: Int?): StepDisplay = when {
    manualSteps != null -> StepDisplay(manualSteps.toLong(), StepSource.MANUAL)
    healthSteps != null -> StepDisplay(healthSteps, StepSource.HEALTH_CONNECT)
    else -> StepDisplay(null, StepSource.UNAVAILABLE)
}

fun validLearningMinutes(value: Int?): Int? = value?.takeIf { it in 1..1440 }

fun validWaterMilliliters(value: Int?): Int? = value?.takeIf { it in 1..10000 }

fun validSteps(value: Int?): Int? = value?.takeIf { it in 0..200000 }
