package com.infinityball.pitchpact.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.infinityball.pitchpact.domain.Fixture
import com.infinityball.pitchpact.domain.Scheduling

/** Explicit opt-in only. Inexact alarms may be delayed by battery policy; never sent to server. */
object LocalGameReminders {
    const val CHANNEL = "game_reminders"
    fun schedule(context: Context, fixture: Fixture) {
        val at = Scheduling.reminderEpochMillis(fixture) ?: return
        if (at <= System.currentTimeMillis()) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, GameReminderReceiver::class.java)
            .putExtra("title", fixture.title).putExtra("fixtureId", fixture.id)
        val operation = PendingIntent.getBroadcast(context, fixture.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }
    fun cancel(context: Context, fixtureId: String) {
        val intent = Intent(context, GameReminderReceiver::class.java)
        val operation = PendingIntent.getBroadcast(context, fixtureId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (operation != null) context.getSystemService(AlarmManager::class.java).cancel(operation)
    }
}

class GameReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(LocalGameReminders.CHANNEL,
            "Game reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val title = intent.getStringExtra("title") ?: return
        val notification = android.app.Notification.Builder(context, LocalGameReminders.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Upcoming game").setContentText(title).setAutoCancel(true).build()
        manager.notify(intent.getStringExtra("fixtureId")?.hashCode() ?: title.hashCode(), notification)
    }
}
