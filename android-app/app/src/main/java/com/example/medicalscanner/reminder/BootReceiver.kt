package com.example.medicalscanner.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            MedicineReminderManager.scheduleAll(context)
            AppointmentReminderManager.scheduleAll(context)
        }
    }
}
