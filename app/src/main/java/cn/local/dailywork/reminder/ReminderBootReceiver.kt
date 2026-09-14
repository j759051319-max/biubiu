package cn.local.dailywork.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.local.dailywork.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(context).habitDao().all()
                    .filter { it.reminderEnabled }
                    .forEach { ReminderAlarm.schedule(context, it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
