package cn.local.dailywork.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.local.dailywork.R
import cn.local.dailywork.data.Habit
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val habitId = inputData.getLong(KEY_HABIT_ID, -1)
        val title = inputData.getString(KEY_TITLE) ?: "工作打卡"
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "工作提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("该记录今日工作了")
                .setContentText(title)
                .setAutoCancel(true)
                .build()
            manager.notify(habitId.toInt(), notification)
        }
        schedule(applicationContext, Habit(id = habitId, title = title, reminderEnabled = true, reminderHour = inputData.getInt(KEY_HOUR, 20), reminderMinute = inputData.getInt(KEY_MINUTE, 0)))
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "daily_work_reminders"
        private const val KEY_HABIT_ID = "habit_id"
        private const val KEY_TITLE = "title"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        fun schedule(context: Context, habit: Habit) {
            val workName = "habit-reminder-${habit.id}"
            val manager = WorkManager.getInstance(context)
            if (!habit.reminderEnabled) { manager.cancelUniqueWork(workName); return }
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(habit.reminderHour, habit.reminderMinute))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes().coerceAtLeast(1)
            val data = Data.Builder().putLong(KEY_HABIT_ID, habit.id).putString(KEY_TITLE, habit.title).putInt(KEY_HOUR, habit.reminderHour).putInt(KEY_MINUTE, habit.reminderMinute).build()
            val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MINUTES).setInputData(data).build()
            manager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
