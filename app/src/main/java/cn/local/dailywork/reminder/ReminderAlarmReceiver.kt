package cn.local.dailywork.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.local.dailywork.data.Habit

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getLongExtra(ReminderAlarm.EXTRA_HABIT_ID, -1)
        val title = intent.getStringExtra(ReminderAlarm.EXTRA_TITLE) ?: "每日习惯"
        if (habitId < 0) return
        ReminderAlarm.showNotification(context, habitId, title)
        ReminderAlarm.schedule(
            context,
            Habit(
                id = habitId,
                title = title,
                reminderEnabled = true,
                reminderHour = intent.getIntExtra(ReminderAlarm.EXTRA_HOUR, 20),
                reminderMinute = intent.getIntExtra(ReminderAlarm.EXTRA_MINUTE, 0)
            )
        )
    }
}
