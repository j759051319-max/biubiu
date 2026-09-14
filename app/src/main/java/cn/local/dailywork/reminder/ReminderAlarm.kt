package cn.local.dailywork.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.local.dailywork.MainActivity
import cn.local.dailywork.data.Habit
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object ReminderAlarm {
    const val CHANNEL_ID = "daily_work_reminders"
    const val EXTRA_HABIT_ID = "habit_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, habit: Habit) {
        cancel(context, habit.id)
        if (!habit.reminderEnabled) return
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(habit.reminderHour, habit.reminderMinute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = reminderIntent(context, habit)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (canScheduleExact(context)) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, habitId: Long) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            habitId.toInt(),
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun showNotification(context: Context, habitId: Long, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "每日习惯提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val openApp = PendingIntent.getActivity(
            context,
            habitId.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("记录自己每天")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(habitId.toInt(), notification)
    }

    private fun reminderIntent(context: Context, habit: Habit): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_HABIT_ID, habit.id)
            .putExtra(EXTRA_TITLE, habit.title)
            .putExtra(EXTRA_HOUR, habit.reminderHour)
            .putExtra(EXTRA_MINUTE, habit.reminderMinute)
        return PendingIntent.getBroadcast(
            context,
            habit.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
